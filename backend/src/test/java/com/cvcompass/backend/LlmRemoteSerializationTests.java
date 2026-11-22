package com.cvcompass.backend;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.cvcompass.backend.candidate.dto.*;
import com.cvcompass.backend.candidate.mapper.CandidateValueMapper;
import com.cvcompass.backend.candidate.repository.CandidateProfileRepository;
import com.cvcompass.backend.candidate.repository.PublicationRepository;
import com.cvcompass.backend.candidate.service.*;
import com.cvcompass.backend.ingestion.candidateapi.CandidateDocuments;
import com.cvcompass.backend.mapping.api.CompletedMappings;
import com.cvcompass.backend.mapping.dto.MappingClaim;
import com.cvcompass.backend.mapping.repository.CompletedMappingRepository;
import com.cvcompass.backend.mapping.service.*;
import java.time.Instant;
import java.util.*;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

/** Runs production verification, publication, queries and revision serialization without a database. */
class LlmRemoteSerializationTests {
    private final CandidateProfileRepository profiles = mock(CandidateProfileRepository.class);
    private final PublicationRepository publications = mock(PublicationRepository.class);
    private final CompletedMappingRepository mappings = mock(CompletedMappingRepository.class);
    private final CandidateDocuments documents = mock(CandidateDocuments.class);
    private final List<ExperienceReadRow> rows = new ArrayList<>();
    private final List<Proposal> proposals = new ArrayList<>();
    private final List<History> history = new ArrayList<>();
    private ObjectNode basic = LlmRemoteContractFixtures.JSON.createObjectNode();
    private ObjectNode extensions =
            (ObjectNode)
                    LlmRemoteContractFixtures.JSON.readTree("{\"schemaVersion\":1,\"issues\":[]}");
    private long version;

    @Test
    void serializesInitialProfileAndSubsequentProposalsFromVerifiedCompactOutput()
            throws Exception {
        var json = LlmRemoteContractFixtures.JSON;
        when(documents.valid(12)).thenReturn(true);
        when(documents.lockAndResolve(12)).thenReturn(1L);
        when(documents.list(1))
                .thenReturn(
                        List.of(
                                new CandidateDocuments.Document(
                                        12, "synthetic.pdf", LlmRemoteContractFixtures.AT)));
        when(profiles.exists(1)).thenReturn(true);
        when(profiles.lock(1)).thenAnswer(call -> List.of(version));
        when(profiles.detail(1))
                .thenAnswer(
                        call ->
                                List.of(
                                        new ProfileCore(
                                                version,
                                                basic.deepCopy(),
                                                "UNVERIFIED",
                                                "MAPPING",
                                                extensions.deepCopy())));
        when(profiles.experienceCount(1)).thenAnswer(call -> rows.size());
        when(profiles.experiences(1)).thenAnswer(call -> List.copyOf(rows));
        when(profiles.proposals(1, 100)).thenAnswer(call -> List.copyOf(proposals));
        when(profiles.history(1, null, 100)).thenAnswer(call -> List.copyOf(history));
        doAnswer(
                        call -> {
                            JsonNode data = call.getArgument(2);
                            var fields = new CandidateValueMapper().experience(data);
                            rows.add(
                                    new ExperienceReadRow(
                                            new Experience(
                                                    call.getArgument(0),
                                                    "UNVERIFIED",
                                                    "MAPPING",
                                                    12L,
                                                    call.getArgument(6),
                                                    data),
                                            fields.startDate(),
                                            fields.startPrecision(),
                                            fields.endDate(),
                                            fields.endPrecision(),
                                            fields.ongoingAsStated()));
                            return null;
                        })
                .when(profiles)
                .insertExperience(
                        any(), eq(1L), any(), anyString(), anyString(), eq(12L), anyLong());
        doAnswer(
                        call -> {
                            basic = ((ObjectNode) call.getArgument(1)).deepCopy();
                            extensions = ((ObjectNode) call.getArgument(5)).deepCopy();
                            return null;
                        })
                .when(profiles)
                .mappedBasic(eq(1L), any(), any(), anyString(), anyString(), any());
        doAnswer(
                        call -> {
                            extensions.setAll(
                                    (ObjectNode) json.readTree((String) call.getArgument(1)));
                            return null;
                        })
                .when(publications)
                .extensions(eq(1L), anyString());
        doAnswer(
                        call -> {
                            CompletedMappings.Result result = call.getArgument(2);
                            proposals.add(
                                    new Proposal(
                                            call.getArgument(0),
                                            12,
                                            result.id(),
                                            call.getArgument(3)));
                            return null;
                        })
                .when(publications)
                .propose(any(), eq(1L), any(), any());
        when(publications.proposePersonal(eq(1L), any(), any()))
                .thenAnswer(
                        call -> {
                            CompletedMappings.Result result = call.getArgument(1);
                            proposals.add(
                                    new Proposal(
                                            UUID.randomUUID(),
                                            12,
                                            result.id(),
                                            call.getArgument(2),
                                            "PERSONAL_INFO"));
                            return true;
                        });
        doAnswer(
                        call -> {
                            version++;
                            history.addFirst(
                                    new History(
                                            version,
                                            version,
                                            call.getArgument(2),
                                            call.getArgument(3),
                                            json.readTree((String) call.getArgument(4)),
                                            json.readTree((String) call.getArgument(5)),
                                            LlmRemoteContractFixtures.AT));
                            return null;
                        })
                .when(profiles)
                .revision(eq(1L), anyLong(), anyString(), anyString(), anyString(), anyString());
        var queries = new CandidateQueryService(profiles, documents);
        var revisions = new CandidateRevisionService(profiles, queries, json);
        var publication =
                new PublicationService(
                        publications,
                        profiles,
                        new CompletedMappings(mappings, json),
                        documents,
                        queries,
                        revisions,
                        new CandidateExperienceService(profiles, new CandidateValueMapper()),
                        json,
                        event -> {});
        var softly = new org.assertj.core.api.SoftAssertions();
        for (boolean subsequent : List.of(false, true)) {
            long resultId = subsequent ? 102 : 101;
            var input = MappingRemoteFixtures.input();
            String hash = MappingDigests.sha256(MappingDigests.canonical(input));
            var claim =
                    new MappingClaim(
                            1,
                            11,
                            13,
                            LlmRemoteContractFixtures.definition().revision(),
                            "llm-extraction/1.0",
                            1,
                            UUID.randomUUID(),
                            Instant.MAX,
                            LlmRemoteContractFixtures.definition());
            var verified =
                    new MappingRemoteVerifier()
                            .verify(
                                    claim,
                                    input,
                                    hash,
                                    LlmRemoteContractFixtures.compact(hash, subsequent),
                                    MappingRemoteFixtures.provenance());
            when(mappings.read(resultId))
                    .thenReturn(
                            Optional.of(
                                    new CompletedMappings.Result(
                                            resultId,
                                            3,
                                            "NEEDS_REVIEW",
                                            MappingDigests.canonical(verified),
                                            11,
                                            13,
                                            12)));
            assertThat(publication.publish(resultId)).isTrue();
            assertThat(rows).hasSize(5);
            assertThat(proposals).hasSize(subsequent ? 6 : 0);
            String prefix = subsequent ? "subsequent-" : "initial-";
            // Write all outputs before comparing, so drift evidence is complete.
            Map<String, JsonNode> outputs =
                    Map.of(
                            "profile",
                            json.valueToTree(queries.detail(1)),
                            "proposals",
                            json.valueToTree(queries.proposals(1, null, 100)),
                            "history",
                            json.valueToTree(revisions.history(1, null, 100)));
            outputs.forEach(
                    (name, value) ->
                            softly.check(
                                    () ->
                                            LlmRemoteContractFixtures.assertFixture(
                                                    prefix + name, value)));
        }
        softly.assertAll();
    }
}
