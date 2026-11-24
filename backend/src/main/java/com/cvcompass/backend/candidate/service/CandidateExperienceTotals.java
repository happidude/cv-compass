package com.cvcompass.backend.candidate.service;

import com.cvcompass.backend.candidate.dto.ExperienceReadRow;
import com.cvcompass.backend.candidate.dto.ExperienceSummary;
import com.cvcompass.backend.candidate.dto.ExperienceTotalsInput;
import com.cvcompass.backend.candidate.metrics.ExperienceMetrics;
import com.cvcompass.backend.candidate.repository.CandidateProfileRepository;
import com.cvcompass.backend.ingestion.candidateapi.CandidateDocuments;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;

@Service
public class CandidateExperienceTotals implements ExperienceMetrics {
    private final CandidateProfileRepository repository;
    private final CandidateDocuments documents;

    public CandidateExperienceTotals(
            CandidateProfileRepository repository, CandidateDocuments documents) {
        this.repository = repository;
        this.documents = documents;
    }

    @Override
    public long totalMonths(long candidateId) {
        return summarize(repository.experiences(candidateId), documents.list(candidateId))
                .totalMonths();
    }

    ExperienceSummary summarize(
            List<ExperienceReadRow> experienceRows,
            List<CandidateDocuments.Document> linkedDocuments) {
        var importedDates =
                linkedDocuments.stream()
                        .collect(
                                Collectors.toMap(
                                        CandidateDocuments.Document::id,
                                        doc ->
                                                Instant.parse(doc.importedAt())
                                                        .atOffset(ZoneOffset.UTC)
                                                        .toLocalDate()));
        return ExperienceTotals.calculate(
                experienceRows.stream().map(CandidateExperienceTotals::totalEntry).toList(),
                importedDates);
    }

    private static ExperienceTotalsInput totalEntry(ExperienceReadRow row) {
        var experience = row.experience();
        return new ExperienceTotalsInput(
                experience.id(),
                row.startDate(),
                row.startPrecision(),
                row.endDate(),
                row.endPrecision(),
                row.ongoingAsStated(),
                experience.data().path("period").path("state").asString(),
                false,
                experience.data().path("countInTotal").asBoolean(true),
                experience.documentId());
    }
}
