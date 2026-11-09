package com.cvcompass.backend.candidate.service;

import com.cvcompass.backend.candidate.dto.Profile;
import com.cvcompass.backend.candidate.exception.CandidateFailure;
import com.cvcompass.backend.candidate.mapper.CandidateValueMapper;
import com.cvcompass.backend.candidate.mapping.PersonalInfoMapper;
import com.cvcompass.backend.candidate.repository.CandidateProfileRepository;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

@Service
public class CandidateReviewService {
    private final CandidateProfileRepository repository;
    private final CandidateQueryService queries;
    private final CandidateRevisionService revisions;
    private final CandidateExperienceService experiences;
    private final CandidateValidator validator;

    public CandidateReviewService(
            CandidateProfileRepository repository,
            CandidateQueryService queries,
            CandidateRevisionService revisions,
            CandidateExperienceService experiences,
            CandidateValidator validator) {
        this.repository = repository;
        this.queries = queries;
        this.revisions = revisions;
        this.experiences = experiences;
        this.validator = validator;
    }

    @Transactional
    public Profile decide(
            long id,
            long version,
            UUID proposalId,
            String decision,
            UUID target,
            JsonNode edited,
            String actor) {
        revisions.expected(id, version);
        var before = queries.detail(id);
        var rows = repository.lockProposal(id, proposalId);
        if (rows.isEmpty()) throw new CandidateFailure(409, "PROPOSAL_CLOSED");
        var proposal = rows.getFirst();
        if (!List.of("ACCEPT", "EDIT", "REJECT").contains(decision))
            throw new CandidateFailure(400, "VALIDATION_ERROR");
        if ("PERSONAL_INFO".equals(proposal.kind())) {
            if (target != null) throw new CandidateFailure(400, "VALIDATION_ERROR");
            if (!"REJECT".equals(decision)) {
                var patch = "EDIT".equals(decision) ? edited : proposal.data().path("patch");
                validator.validateBasic(patch);
                var merged = PersonalInfoMapper.merge(before.basic(), patch);
                repository.mappedBasic(
                        id,
                        merged,
                        new CandidateValueMapper().basic(merged),
                        "EDIT".equals(decision) ? "EDITED" : "CONFIRMED",
                        "EDIT".equals(decision) ? "USER" : "MAPPING",
                        PersonalInfoMapper.extensions(
                                before.extensions(),
                                proposal.data().path("metadata"),
                                patch.propertyNames(),
                                "EDIT".equals(decision)));
            }
        } else if (!"REJECT".equals(decision)) {
            var old =
                    target == null
                            ? null
                            : before.experiences().stream()
                                    .filter(e -> e.id().equals(target))
                                    .findFirst()
                                    .orElseThrow(
                                            () ->
                                                    new CandidateFailure(
                                                            404, "EXPERIENCE_NOT_FOUND"));
            var data = "EDIT".equals(decision) ? validator.userExperience(edited) : proposal.data();
            if ("ACCEPT".equals(decision)
                    && old != null
                    && old.data().path("countInTotal").isBoolean()) {
                var replacement = (ObjectNode) data.deepCopy();
                replacement.set("countInTotal", old.data().get("countInTotal"));
                data = replacement;
            }
            String origin = "EDIT".equals(decision) ? "USER" : "MAPPING";
            String state = "EDIT".equals(decision) ? "EDITED" : "CONFIRMED";
            if (old == null)
                experiences.insertExperience(
                        id,
                        data,
                        state,
                        origin,
                        "EDIT".equals(decision) ? null : proposal.documentId(),
                        "EDIT".equals(decision) ? null : proposal.resultId());
            else
                experiences.updateExperience(
                        old.id(),
                        data,
                        state,
                        origin,
                        "EDIT".equals(decision) ? null : proposal.documentId(),
                        "EDIT".equals(decision) ? null : proposal.resultId());
        }
        repository.decide(proposalId, decision, actor);
        revisions.revision(
                id,
                version,
                actor,
                decision + "_PROPOSAL",
                Map.of("profile", before, "proposal", proposal),
                Map.of(
                        "profile",
                        queries.detail(id),
                        "proposalId",
                        proposalId,
                        "decision",
                        decision));
        return queries.detail(id);
    }
}
