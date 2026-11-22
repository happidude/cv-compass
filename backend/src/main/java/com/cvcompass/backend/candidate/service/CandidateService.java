package com.cvcompass.backend.candidate.service;

import com.cvcompass.backend.candidate.dto.Profile;
import com.cvcompass.backend.candidate.events.CandidateChanged;
import com.cvcompass.backend.candidate.exception.CandidateFailure;
import com.cvcompass.backend.candidate.mapper.CandidateValueMapper;
import com.cvcompass.backend.candidate.mapping.PersonalInfoMapper;
import com.cvcompass.backend.candidate.repository.CandidateProfileRepository;
import com.cvcompass.backend.ingestion.candidateapi.CandidateDocuments;
import java.util.UUID;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.JsonNode;

@Service
public class CandidateService {
    private final ApplicationEventPublisher events;
    private final CandidateProfileRepository repository;
    private final CandidateQueryService queries;
    private final CandidateRevisionService revisions;
    private final CandidateExperienceService experiences;
    private final CandidateValidator validator;
    private final CandidateValueMapper mapper;
    private final CandidateDocuments documents;

    public CandidateService(
            CandidateProfileRepository repository,
            CandidateQueryService queries,
            CandidateRevisionService revisions,
            CandidateExperienceService experiences,
            CandidateValidator validator,
            CandidateValueMapper mapper,
            CandidateDocuments documents,
            ApplicationEventPublisher events) {
        this.events = events;
        this.repository = repository;
        this.queries = queries;
        this.revisions = revisions;
        this.experiences = experiences;
        this.validator = validator;
        this.mapper = mapper;
        this.documents = documents;
    }

    @Transactional
    public Profile attach(long id, long version, long documentId, String actor) {
        if (!documents.valid(documentId)) throw new CandidateFailure(404, "DOCUMENT_NOT_FOUND");
        documents.lock(documentId);
        revisions.expected(id, version);
        var before = queries.detail(id);
        if (!documents.attach(documentId, id))
            throw new CandidateFailure(409, "DOCUMENT_ALREADY_LINKED");
        revisions.revision(id, version, actor, "LINK_DOCUMENT", before, queries.detail(id));
        events.publishEvent(new CandidateChanged(id));
        return queries.detail(id);
    }

    @Transactional
    public Profile basic(long id, long version, JsonNode value, boolean confirm, String actor) {
        revisions.expected(id, version);
        Profile before = queries.detail(id);
        if (!confirm) {
            validator.validateBasic(value);
            var actual = (tools.jackson.databind.node.ObjectNode) value.deepCopy();
            var changed = new java.util.HashSet<String>();
            for (String field : value.propertyNames()) {
                boolean equal =
                        value.get(field).isNull()
                                ? before.basic().path(field).isMissingNode()
                                        || before.basic().path(field).isNull()
                                : PersonalInfoMapper.same(
                                        field,
                                        before.basic().path(field),
                                        value.get(field).asString());
                if (equal) actual.remove(field);
                else changed.add(field);
            }
            if (changed.isEmpty()) return before;
            var merged = PersonalInfoMapper.merge(before.basic(), actual);
            repository.mappedBasic(
                    id,
                    merged,
                    mapper.basic(merged),
                    "EDITED",
                    "USER",
                    PersonalInfoMapper.extensions(
                            before.extensions(),
                            before.extensions().path("personalInfo"),
                            changed,
                            true));
        } else {
            if ("CONFIRMED".equals(before.basicState())) return before;
            repository.confirmBasic(id);
        }
        revisions.revision(
                id,
                version,
                actor,
                confirm ? "CONFIRM_BASIC" : "EDIT_BASIC",
                before,
                queries.detail(id));
        events.publishEvent(new CandidateChanged(id));
        return queries.detail(id);
    }

    @Transactional
    public Profile editExperience(
            long id,
            long version,
            UUID experienceId,
            JsonNode value,
            String operation,
            String actor) {
        revisions.expected(id, version);
        var before = queries.detail(id);
        var existing =
                experienceId == null
                        ? null
                        : before.experiences().stream()
                                .filter(e -> e.id().equals(experienceId))
                                .findFirst()
                                .orElseThrow(
                                        () -> new CandidateFailure(404, "EXPERIENCE_NOT_FOUND"));
        switch (operation) {
            case "DELETE" -> {
                if (existing == null) throw new CandidateFailure(400, "VALIDATION_ERROR");
                repository.deleteExperience(experienceId);
            }
            case "CONFIRM" -> {
                if (existing == null) throw new CandidateFailure(400, "VALIDATION_ERROR");
                repository.confirmExperience(experienceId);
            }
            case "SAVE" -> {
                JsonNode clean = validator.userExperience(value);
                if (existing == null)
                    experiences.insertExperience(id, clean, "EDITED", "USER", null, null);
                else
                    experiences.updateExperience(
                            existing.id(), clean, "EDITED", "USER", existing.documentId(), null);
            }
            default -> throw new CandidateFailure(400, "VALIDATION_ERROR");
        }
        revisions.revision(
                id, version, actor, operation + "_EXPERIENCE", before, queries.detail(id));
        events.publishEvent(new CandidateChanged(id));
        return queries.detail(id);
    }
}
