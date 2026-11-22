package com.cvcompass.backend.catalog.service;

import com.cvcompass.backend.candidate.events.CandidateChanged;
import com.cvcompass.backend.catalog.Fold;
import com.cvcompass.backend.catalog.TaxonomyCodes;
import com.cvcompass.backend.catalog.config.NormalizationClock;
import com.cvcompass.backend.catalog.config.NormalizationProperties;
import com.cvcompass.backend.catalog.dto.NormalizationProtocol.Claim;
import com.cvcompass.backend.catalog.dto.NormalizationProtocol.Rejection;
import com.cvcompass.backend.catalog.dto.NormalizationProtocol.Submission;
import com.cvcompass.backend.catalog.exception.NormalizationFailure;
import com.cvcompass.backend.catalog.repository.NormalizationCatalogRepository;
import com.cvcompass.backend.catalog.repository.NormalizationTaskRepository;
import com.cvcompass.backend.catalog.repository.NormalizationTaskRepository.Task;
import com.cvcompass.backend.catalog.service.NormalizationLinkService.Alias;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.UUID;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.dao.ConcurrencyFailureException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.JsonNode;

@Service
public class NormalizationService {
    private final NormalizationTaskRepository tasks;
    private final NormalizationCatalogRepository catalog;
    private final NormalizationLinkService links;
    private final TaxonomyCodes taxonomy;
    private final NormalizationProperties properties;
    private final NormalizationClock clock;
    private final ApplicationEventPublisher events;

    public NormalizationService(
            NormalizationTaskRepository tasks,
            NormalizationCatalogRepository catalog,
            NormalizationLinkService links,
            TaxonomyCodes taxonomy,
            NormalizationProperties properties,
            NormalizationClock clock,
            ApplicationEventPublisher events) {
        this.tasks = tasks;
        this.catalog = catalog;
        this.links = links;
        this.taxonomy = taxonomy;
        this.properties = properties;
        this.clock = clock;
        this.events = events;
    }

    @Transactional
    public Claim claim(int max) {
        if (max < 1 || max > properties.getClaimMaxItems())
            throw new NormalizationFailure(HttpStatus.BAD_REQUEST, "INVALID_RESULT");
        Instant now = clock.clock().instant(), expires = now.plus(properties.getLeaseDuration());
        UUID token = UUID.randomUUID();
        var items = tasks.claim(max, token, now, expires);
        return items.isEmpty()
                ? null
                : new Claim(token, expires, taxonomy.version(), tasks.taxonomy(), items);
    }

    @Transactional(isolation = Isolation.REPEATABLE_READ)
    public Submission submit(UUID token, List<JsonNode> results) {
        if (token == null
                || results == null
                || results.isEmpty()
                || results.size() > properties.getClaimMaxItems())
            throw new NormalizationFailure(HttpStatus.BAD_REQUEST, "INVALID_RESULT");
        List<Task> lease;
        try {
            lease = tasks.lockLease(token);
        } catch (ConcurrencyFailureException lost) {
            // A concurrent submit consumed these locked lease rows after our snapshot began.
            throw new NormalizationFailure(HttpStatus.CONFLICT, "LEASE_LOST");
        }
        Instant now = clock.clock().instant();
        if (lease.isEmpty() || lease.stream().anyMatch(t -> !t.expiresAt().isAfter(now)))
            throw new NormalizationFailure(HttpStatus.CONFLICT, "LEASE_LOST");
        var byId = new HashMap<Long, Task>();
        lease.forEach(t -> byId.put(t.id(), t));
        var rejected = new ArrayList<Rejection>();
        var entries = new LinkedHashMap<Alias, Long>();
        int accepted = 0;
        for (var result : results) {
            long id =
                    result.path("id").isIntegralNumber() && result.path("id").canConvertToLong()
                            ? result.path("id").asLong()
                            : -1;
            var task = byId.remove(id);
            if (task == null) {
                rejected.add(new Rejection(id, "UNKNOWN_TASK"));
                continue;
            }
            String error = validate(task, result);
            if (error != null) {
                tasks.retry(id, error, now, properties.getRetryBackoff().toMillis());
                rejected.add(new Rejection(id, error));
                continue;
            }
            try {
                long entry =
                        catalog.withSavepoint(
                                () -> {
                                    long created = createEntry(task, result);
                                    tasks.done(id, created, now);
                                    return created;
                                });
                entries.put(new Alias(task.kind(), task.folded()), entry);
                accepted++;
            } catch (DataIntegrityViolationException | NormalizationFailure invalid) {
                tasks.retry(id, "INVALID_RESULT", now, properties.getRetryBackoff().toMillis());
                rejected.add(new Rejection(id, "INVALID_RESULT"));
            }
        }
        for (long id : links.linkMatches(entries)) events.publishEvent(new CandidateChanged(id));
        return new Submission(accepted, List.copyOf(rejected));
    }

    private long createEntry(Task task, JsonNode result) {
        if (task.kind().equals("COMPANY")) {
            var company = result.path("company");
            long entry =
                    catalog.company(
                            text(company, "name"),
                            text(company, "industry"),
                            text(company, "proposedLabel"));
            String parent = text(company, "parent");
            if (parent != null && !catalog.parent(entry, catalog.company(parent, null, null)))
                throw new NormalizationFailure(HttpStatus.BAD_REQUEST, "INVALID_RESULT");
            return entry;
        }
        var title = result.path("title");
        return catalog.title(
                task.raw(),
                functions(title),
                text(title, "seniority"),
                text(title, "scope"),
                text(title, "proposedLabel"));
    }

    private String validate(Task task, JsonNode result) {
        boolean company = result.hasNonNull("company"),
                title = result.hasNonNull("title"),
                error = result.hasNonNull("error");
        if ((company ? 1 : 0) + (title ? 1 : 0) + (error ? 1 : 0) != 1) return "INVALID_RESULT";
        if (error) {
            String code = text(result, "error");
            return code != null && code.matches("[A-Z][A-Z0-9_]{0,39}") ? code : "INVALID_RESULT";
        }
        if (company != task.kind().equals("COMPANY")) return "INVALID_RESULT";
        var value = result.path(company ? "company" : "title");
        if (!value.isObject()) return "INVALID_RESULT";
        if (!optionalString(value, "proposedLabel")) return "INVALID_RESULT";
        if (company) {
            if (!name(text(value, "name")) || !optionalString(value, "parent"))
                return "INVALID_RESULT";
            String parent = text(value, "parent");
            if (parent != null
                    && (!name(parent)
                            || Fold.fold(parent).equals(Fold.fold(text(value, "name")))
                            || catalog.wouldCycle(text(value, "name"), parent)))
                return "INVALID_RESULT";
            String industry = text(value, "industry");
            if (!taxonomy.isActive("INDUSTRY", industry)) return "INVALID_CODE";
            if ("OTHER".equals(industry) && blank(text(value, "proposedLabel")))
                return "PROPOSED_LABEL_REQUIRED";
        } else {
            var array = value.path("functions");
            if (!array.isArray()
                    || array.size() < 1
                    || array.size() > 2
                    || array.valueStream().anyMatch(n -> !n.isString())) return "INVALID_RESULT";
            var functions = functions(value);
            if (new HashSet<>(functions).size() != functions.size()) return "INVALID_RESULT";
            if (functions.stream().anyMatch(f -> !taxonomy.isActive("FUNCTION", f))
                    || !taxonomy.isActive("SENIORITY", text(value, "seniority")))
                return "INVALID_CODE";
            if (!optionalString(value, "scope")) return "INVALID_RESULT";
            String scope = text(value, "scope");
            if (scope != null && !taxonomy.isActive("SCOPE", scope)) return "INVALID_CODE";
            if (functions.contains("OTHER") && blank(text(value, "proposedLabel")))
                return "PROPOSED_LABEL_REQUIRED";
        }
        return null;
    }

    private boolean name(String name) {
        return !blank(name)
                && name.indexOf('\0') < 0
                && name.strip().codePointCount(0, name.strip().length())
                        <= properties.getMaxFieldCodePoints()
                && !Fold.fold(name).isBlank();
    }

    private boolean blank(String value) {
        return value == null
                || value.indexOf('\0') >= 0
                || !catalog.hasNonWhitespace(value.strip());
    }

    private static boolean optionalString(JsonNode value, String field) {
        return !value.hasNonNull(field)
                || (value.path(field).isString() && value.path(field).asString().indexOf('\0') < 0);
    }

    private static String text(JsonNode value, String field) {
        return value.path(field).isString() ? value.path(field).asString() : null;
    }

    private static List<String> functions(JsonNode value) {
        return value.path("functions").valueStream().map(n -> n.asString()).toList();
    }
}
