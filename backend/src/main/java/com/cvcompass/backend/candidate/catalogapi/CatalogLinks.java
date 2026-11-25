package com.cvcompass.backend.candidate.catalogapi;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** Catalog association boundary; writes require the caller's transaction. */
public interface CatalogLinks {
    List<ExperienceLink> experiences(long candidateId);

    Optional<ProfileLink> profile(long candidateId);

    /** Locks the same profile row as HR writers before reading an index snapshot. */
    Optional<IndexProfile> lockIndexProfile(long candidateId);

    void metrics(long candidateId, ProfileLink metrics);

    long lastCandidateId();

    List<Long> indexCandidates(long after, long through, int limit);

    record IndexProfile(String fullName, String location, String summary) {}

    /** Updates only the selected, non-deleted experience of this candidate. */
    boolean normalize(long candidateId, UUID experienceId, Normalization normalization);

    record Normalization(
            Long companyId,
            Long titleId,
            String scopeCode,
            String origin,
            String companyNormalized,
            List<String> functionCodes,
            List<String> industryCodes,
            String seniorityCode) {}

    record ExperienceLink(
            UUID id,
            long candidateId,
            Long companyId,
            Long titleId,
            String scopeCode,
            String normalizationOrigin,
            String companyRaw,
            String companyNormalized,
            String titleRaw,
            String functionCodes,
            String industryCodes,
            String seniorityCode,
            LocalDate startDate,
            String startPrecision,
            LocalDate endDate,
            String endPrecision,
            boolean ongoingAsStated,
            Long documentId,
            String data) {}

    record ProfileLink(
            long candidateId,
            String locationProvince,
            Integer totalExperienceMonths,
            Long currentCompanyId,
            Long currentTitleId) {}
}
