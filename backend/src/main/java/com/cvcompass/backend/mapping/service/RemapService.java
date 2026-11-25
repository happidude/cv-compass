package com.cvcompass.backend.mapping.service;

import com.cvcompass.backend.ingestion.api.OcrExtractionReader;
import com.cvcompass.backend.mapping.config.MappingProperties;
import com.cvcompass.backend.mapping.config.RemapProperties;
import com.cvcompass.backend.mapping.dto.MappingVersions;
import com.cvcompass.backend.mapping.dto.RemapRequest;
import com.cvcompass.backend.mapping.dto.RemapRun;
import com.cvcompass.backend.mapping.exception.RemapFailure;
import com.cvcompass.backend.mapping.repository.MappingRepository;
import com.cvcompass.backend.mapping.repository.RemapRepository;
import java.util.List;
import java.util.Set;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;

@Service
public class RemapService {
    private static final Set<String> ISSUE_CODES =
            Set.of(
                    "INVALID_PHONE",
                    "INVALID_EMAIL",
                    "INVALID_NAME",
                    "INVALID_DATE_OF_BIRTH",
                    "VALUE_LENGTH_INVALID",
                    "UNRECOGNIZED_GENDER",
                    "GENDER_UNSUPPORTED",
                    "FIELD_DISABLED",
                    "PHONE_TRUNK_NOTATION_AMBIGUOUS",
                    "PHONE_MULTIPLE_FIRST_KEPT",
                    "LOCATION_PROVINCE_UNRECOGNIZED",
                    "DATE_OF_BIRTH_MONTH_FIRST",
                    "DATE_OF_BIRTH_DAY_FIRST_ASSUMED",
                    "READING_ORDER_UNVERIFIED",
                    "CONFLICTING_VALUES",
                    "UNLABELED_SINGLE_VALUE",
                    "LEGACY_UNPAGED",
                    "OCR_UNUSABLE",
                    "NO_OK_PAGES",
                    "SOURCE_INCOMPLETE",
                    "EXPERIENCE_NOT_FOUND",
                    "ORPHAN_CONTINUATION",
                    "UNLINKED_PAGE_TEXT",
                    "SUMMARY_DETAIL_MERGED",
                    "AMBIGUOUS_SUMMARY_LINK",
                    "POSSIBLE_PERIOD_OVERLAP",
                    "TITLE_CONFLICT",
                    "INVALID_PERIOD",
                    "PERIOD_CONFLICT",
                    "PERIOD_NOT_FOUND",
                    "ONGOING_AS_STATED_UNVERIFIED",
                    "TITLE_NOT_FOUND",
                    "INDUSTRY_UNKNOWN",
                    "FUNCTION_UNKNOWN",
                    "PAGE_CONTINUATION_ASSUMED",
                    "SECTION_RESUMED_AFTER_STOP",
                    "AMBIGUOUS_EXPERIENCE_ROW",
                    "UNRESOLVED_EXPERIENCE_BLOCK",
                    "UNCLASSIFIED_EXPERIENCE_CONTEXT",
                    "UNSCOPED_EMPLOYER_CONTEXT",
                    "ROLE_CONTEXT_UNCLASSIFIED",
                    "EMPLOYER_PERIOD_NOT_ASSIGNED",
                    "MONTH_ABBREVIATION_NONSTANDARD",
                    "TWO_DIGIT_YEAR_ASSUMED");
    private final RemapRepository runs;
    private final MappingRepository jobs;
    private final OcrExtractionReader inputs;
    private final MappingProperties mapping;
    private final RemapProperties properties;
    private final ObjectMapper json;

    public RemapService(
            RemapRepository runs,
            MappingRepository jobs,
            OcrExtractionReader inputs,
            MappingProperties mapping,
            RemapProperties properties,
            ObjectMapper json) {
        this.runs = runs;
        this.jobs = jobs;
        this.inputs = inputs;
        this.mapping = mapping;
        this.properties = properties;
        this.json = json;
    }

    @Transactional
    public RemapRun create(RemapRequest request, String creator) {
        validate(request);
        long config = jobs.lockSettings()[0];
        if (config == 0)
            throw new RemapFailure(HttpStatus.CONFLICT, "MAPPING_ACTIVE_CONFIG_REQUIRED");
        return get(runs.create(request, config, inputs.upperId(), creator));
    }

    @Transactional(readOnly = true)
    public RemapRun get(long id) {
        return runs.find(id)
                .orElseThrow(() -> new RemapFailure(HttpStatus.NOT_FOUND, "REMAP_NOT_FOUND"));
    }

    @Transactional(readOnly = true)
    public List<RemapRun> recent() {
        return runs.recent(properties.getListLimit());
    }

    @Transactional
    public RemapRun pause(long id) {
        return state(id, "PAUSED");
    }

    @Transactional
    public RemapRun resume(long id) {
        return state(id, "RUNNING");
    }

    private RemapRun state(long id, String state) {
        if (!runs.lock(id)) throw new RemapFailure(HttpStatus.NOT_FOUND, "REMAP_NOT_FOUND");
        runs.state(id, state);
        return get(id);
    }

    public List<Long> running() {
        return runs.running();
    }

    @Transactional
    public void advance(long id) {
        // This lock also serializes pause/resume and multiple scheduler instances.
        if (!runs.lock(id)) return;
        var run = get(id);
        if (!"RUNNING".equals(run.state())) return;
        int capacity = (int) Math.max(0, properties.getMaxPending() - run.pending());
        if (capacity == 0) return;
        int limit = Math.min(properties.getBatchSize(), capacity);
        var ids = runs.select(run, limit);
        String engine =
                MappingVersions.config(jobs.definitionJson(run.configId()), json)
                        .engineCompatibility();
        int queued = 0;
        for (long extraction : ids) {
            if (jobs.enqueue(
                    extraction,
                    run.configId(),
                    engine,
                    mapping.getMaxAttempts(),
                    properties.getPriority(),
                    id)) queued++;
        }
        long cursor = ids.isEmpty() ? run.cursorExtractionId() : ids.getLast();
        boolean done = ids.size() < limit || cursor == run.upperExtractionId();
        runs.advance(id, done ? run.upperExtractionId() : cursor, ids.size(), queued, done);
    }

    private void validate(RemapRequest request) {
        boolean valid = request != null && request.scope() != null;
        if (valid)
            valid =
                    switch (request.scope()) {
                        case "ALL" -> true;
                        case "BATCH" -> request.batchId() != null && request.batchId() > 0;
                        case "DATE_RANGE" ->
                                request.from() != null
                                        && request.to() != null
                                        && !request.from().isAfter(request.to())
                                        && request.from().getYear() >= 1
                                        && request.to().getYear() <= 9999;
                        case "ISSUE" ->
                                request.issueCode() != null
                                        && request.issueCode().matches("[A-Z][A-Z0-9_]{0,63}")
                                        && (ISSUE_CODES.contains(request.issueCode())
                                                || runs.hasIssueCode(request.issueCode()));
                        default -> false;
                    };
        if (!valid) throw new RemapFailure(HttpStatus.BAD_REQUEST, "INVALID_REMAP_SCOPE");
    }
}
