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
import org.junit.jupiter.api.BeforeEach;
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
    private String basicState = "UNVERIFIED";
    private CandidateQueryService queries;
    private CandidateRevisionService revisions;
    private PublicationService publication;

    @BeforeEach
    void setUp() {
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
                                                basicState,
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
                            UUID id = call.getArgument(0);
                            rows.removeIf(row -> row.experience().id().equals(id));
                            return null;
                        })
                .when(publications)
                .supersedeMappedExperience(any());
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
        queries =
                new CandidateQueryService(
                        profiles, documents, new CandidateExperienceTotals(profiles, documents));
        revisions = new CandidateRevisionService(profiles, queries, json);
        publication =
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
    }

    @Test
    void serializesInitialProfileAndSubsequentProposalsFromVerifiedCompactOutput()
            throws Exception {
        var json = LlmRemoteContractFixtures.JSON;
        var softly = new org.assertj.core.api.SoftAssertions();
        for (boolean subsequent : List.of(false, true)) {
            if (subsequent) {
                when(publications.publishedDocument(1, 12)).thenReturn(true);
                when(publications.canReplaceMappedExperiences(1, 12)).thenReturn(true);
            }
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
            assertThat(proposals).hasSize(0);
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

    @Test
    void proposesSameDocumentRemappingWhenHrHasConfirmedProfileAndExperience() {
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
                                LlmRemoteContractFixtures.compact(hash, true),
                                MappingRemoteFixtures.provenance());
        var result =
                new CompletedMappings.Result(
                        102, 3, "NEEDS_REVIEW", MappingDigests.canonical(verified), 11, 13, 12);
        when(mappings.read(102)).thenReturn(Optional.of(result));
        when(publications.publishedDocument(1, 12)).thenReturn(true);
        when(publications.canReplaceMappedExperiences(1, 12)).thenReturn(false);
        version = 1;
        basicState = "CONFIRMED";
        basic.put("fullName", "Synthetic HR Reviewer");
        var confirmedBasic = basic.deepCopy();
        JsonNode data =
                LlmRemoteContractFixtures.JSON.valueToTree(verified.experiences().getFirst());
        var fields = new CandidateValueMapper().experience(data);
        var confirmedExperience =
                new ExperienceReadRow(
                        new Experience(UUID.randomUUID(), "CONFIRMED", "MAPPING", 12L, 101L, data),
                        fields.startDate(),
                        fields.startPrecision(),
                        fields.endDate(),
                        fields.endPrecision(),
                        fields.ongoingAsStated());
        rows.add(confirmedExperience);

        assertThat(publication.publish(102)).isTrue();

        assertThat(proposals).hasSize(6);
        assertThat(proposals)
                .extracting(Proposal::kind)
                .containsOnly("EXPERIENCE", "PERSONAL_INFO");
        assertThat(proposals)
                .filteredOn(proposal -> "PERSONAL_INFO".equals(proposal.kind()))
                .singleElement()
                .satisfies(
                        proposal ->
                                assertThat(
                                                proposal.data()
                                                        .path("patch")
                                                        .path("fullName")
                                                        .asString())
                                        .isEqualTo("Synthetic Reviewer"));
        verify(publications).ledger(result, 1L, "PROPOSED");
        verify(profiles, never())
                .mappedBasic(anyLong(), any(), any(), anyString(), anyString(), any());
        verify(publications, never()).supersedeMappedExperience(any());
        verify(profiles, never())
                .insertExperience(
                        any(), anyLong(), any(), anyString(), anyString(), anyLong(), anyLong());
        assertThat(basic).isEqualTo(confirmedBasic);
        assertThat(queries.detail(1).basicState()).isEqualTo("CONFIRMED");
        assertThat(rows).containsExactly(confirmedExperience);
    }
}
