package com.cvcompass.backend.candidate.catalogapi;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** Catalog association boundary; writes require the caller's transaction. */
public interface CatalogLinks {
    List<ExperienceLink> experiences(long candidateId);

    Optional<ProfileLink> profile(long candidateId);

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
