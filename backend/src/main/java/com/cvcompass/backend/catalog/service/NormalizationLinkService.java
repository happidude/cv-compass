package com.cvcompass.backend.catalog.service;

import com.cvcompass.backend.candidate.catalogapi.CatalogLinks;
import com.cvcompass.backend.candidate.catalogapi.CatalogLinks.ExperienceLink;
import com.cvcompass.backend.candidate.events.CandidateChanged;
import com.cvcompass.backend.catalog.Fold;
import com.cvcompass.backend.catalog.api.CatalogLookup;
import com.cvcompass.backend.catalog.config.NormalizationProperties;
import com.cvcompass.backend.catalog.repository.NormalizationCatalogRepository;
import com.cvcompass.backend.catalog.repository.NormalizationTaskRepository;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;

@Service
public class NormalizationLinkService {
    private final CatalogLinks candidates;
    private final CatalogLookup lookup;
    private final NormalizationCatalogRepository catalog;
    private final NormalizationTaskRepository tasks;
    private final ApplicationEventPublisher events;
    private final ObjectMapper json;
    private final NormalizationProperties properties;

    public NormalizationLinkService(
            CatalogLinks candidates,
            CatalogLookup lookup,
            NormalizationCatalogRepository catalog,
            NormalizationTaskRepository tasks,
            ApplicationEventPublisher events,
            ObjectMapper json,
            NormalizationProperties properties) {
        this.candidates = candidates;
        this.lookup = lookup;
        this.catalog = catalog;
        this.tasks = tasks;
        this.events = events;
        this.json = json;
        this.properties = properties;
    }

    @Transactional
    public boolean linkCandidate(long candidateId) {
        if (candidates.lockIndexProfile(candidateId).isEmpty()) return false;
        boolean changed = false;
        for (var experience : candidates.experiences(candidateId)) {
            if ("USER".equals(experience.normalizationOrigin())) continue;
            Long company = experience.companyId();
            Long title = experience.titleId();
            if (company == null) company = resolve("COMPANY", experience.companyRaw(), null);
            if (title == null)
                title = resolve("TITLE", experience.titleRaw(), experience.companyRaw());
            if (!Objects.equals(company, experience.companyId())
                    || !Objects.equals(title, experience.titleId())) {
                changed |= apply(experience, company, title, false);
            }
        }
        return changed;
    }

    private Long resolve(String kind, String raw, String company) {
        String folded = Fold.fold(raw);
        if (folded == null || folded.isBlank()) return null;
        Long id = catalog.alias(kind, folded);
        if (id == null) tasks.enqueue(kind, folded, raw, company);
        return id;
    }

    @Transactional
    public long backfillBatch(long after, long through, int size) {
        var ids = candidates.indexCandidates(after, through, size);
        for (long id : ids) {
            if (linkCandidate(id)) events.publishEvent(new CandidateChanged(id));
        }
        return ids.isEmpty() ? through : ids.getLast();
    }

    public long lastCandidateId() {
        return candidates.lastCandidateId();
    }

    public record Alias(String kind, String folded) {}

    /** The submit transaction uses repeatable read before locking matching profiles. */
    public Set<Long> linkMatches(Map<Alias, Long> entries) {
        if (entries.isEmpty()) return Set.of();
        var affected = new TreeSet<Long>();
        long after = 0, through = candidates.lastCandidateId();
        while (after < through) {
            var ids =
                    candidates.indexCandidates(
                            after, through, properties.getCandidateScanPageSize());
            if (ids.isEmpty()) break;
            for (long id : ids) {
                var experiences = candidates.experiences(id);
                if (experiences.stream().noneMatch(e -> matches(e, entries))) continue;
                // A concurrent HR edit makes this snapshot stale and PostgreSQL rejects the lock.
                if (candidates.lockIndexProfile(id).isEmpty()) continue;
                for (var experience : experiences) {
                    if (!matches(experience, entries)) continue;
                    Long company =
                            entries.get(new Alias("COMPANY", Fold.fold(experience.companyRaw())));
                    Long title = entries.get(new Alias("TITLE", Fold.fold(experience.titleRaw())));
                    boolean linkCompany =
                            company != null
                                    && (experience.companyId() == null
                                            || isAi("COMPANY", experience.companyId()));
                    boolean linkTitle =
                            title != null
                                    && (experience.titleId() == null
                                            || isAi("TITLE", experience.titleId()));
                    if (!linkCompany && !linkTitle) continue;
                    // Both operands are boxed so a missing ID remains null.
                    if (apply(
                            experience,
                            linkCompany ? company : experience.companyId(),
                            linkTitle ? title : experience.titleId(),
                            true)) affected.add(id);
                }
            }
            after = ids.getLast();
        }
        return affected;
    }

    private static boolean matches(ExperienceLink e, Map<Alias, Long> entries) {
        return !"USER".equals(e.normalizationOrigin())
                && (entries.containsKey(new Alias("COMPANY", Fold.fold(e.companyRaw())))
                        || entries.containsKey(new Alias("TITLE", Fold.fold(e.titleRaw()))));
    }

    private boolean isAi(String kind, long id) {
        if (kind.equals("COMPANY")) {
            var entry = lookup.companies(List.of(id)).get(id);
            return entry != null && "AI".equals(entry.origin());
        }
        var entry = lookup.titles(List.of(id)).get(id);
        return entry != null && "AI".equals(entry.origin());
    }

    private boolean apply(ExperienceLink e, Long companyId, Long titleId, boolean submitted) {
        var company =
                companyId == null ? null : lookup.companies(List.of(companyId)).get(companyId);
        var title = titleId == null ? null : lookup.titles(List.of(titleId)).get(titleId);
        boolean ai =
                submitted
                        || "AI".equals(e.normalizationOrigin())
                        || (company != null && "AI".equals(company.origin()))
                        || (title != null && "AI".equals(title.origin()));
        String display =
                company == null
                        ? e.companyNormalized()
                        : company.name()
                                + (company.parentName() == null
                                        ? ""
                                        : " (" + company.parentName() + ")");
        return candidates.normalize(
                e.candidateId(),
                e.id(),
                new CatalogLinks.Normalization(
                        companyId,
                        titleId,
                        title == null ? e.scopeCode() : title.scopeCode(),
                        ai ? "AI" : null,
                        display,
                        title == null ? codes(e.functionCodes()) : title.functionCodes(),
                        company == null
                                ? codes(e.industryCodes())
                                : company.industryCode() == null
                                        ? List.of()
                                        : List.of(company.industryCode()),
                        title == null ? e.seniorityCode() : title.seniorityCode()));
    }

    private List<String> codes(String value) {
        if (value == null) return null;
        var node = json.readTree(value);
        if (node.isNull()) return null;
        return node.valueStream().map(n -> n.asString()).toList();
    }
}
