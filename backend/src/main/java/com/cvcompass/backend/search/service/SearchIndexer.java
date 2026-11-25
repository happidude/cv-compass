package com.cvcompass.backend.search.service;

import com.cvcompass.backend.candidate.catalogapi.CatalogLinks;
import com.cvcompass.backend.candidate.metrics.ExperienceMetrics;
import com.cvcompass.backend.candidate.metrics.ExperienceMonths;
import com.cvcompass.backend.catalog.api.CatalogLookup;
import com.cvcompass.backend.catalog.api.CatalogText;
import com.cvcompass.backend.mapping.api.Provinces;
import com.cvcompass.backend.search.dto.IndexSnapshot;
import com.cvcompass.backend.search.repository.SearchIndexRepository;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Service
public class SearchIndexer {
    private final CatalogLinks candidates;
    private final CatalogLookup catalog;
    private final SearchIndexRepository repository;
    private final ExperienceMetrics experienceMetrics;

    public SearchIndexer(
            CatalogLinks candidates,
            CatalogLookup catalog,
            SearchIndexRepository repository,
            ExperienceMetrics experienceMetrics) {
        this.candidates = candidates;
        this.catalog = catalog;
        this.repository = repository;
        this.experienceMetrics = experienceMetrics;
    }

    @Transactional(propagation = Propagation.NESTED)
    public void rebuild(long candidateId) {
        // The profile lock serializes scheduled rebuilds with publication and HR edits.
        var profile = candidates.lockIndexProfile(candidateId).orElse(null);
        if (profile == null) {
            repository.delete(candidateId);
            return;
        }
        var experiences = candidates.experiences(candidateId);
        var periods =
                experiences.stream()
                        .map(
                                e ->
                                        new ExperienceMonths.Period(
                                                e.startDate(),
                                                e.startPrecision(),
                                                e.endDate(),
                                                e.endPrecision(),
                                                e.ongoingAsStated(),
                                                e.companyId(),
                                                e.titleId()))
                        .toList();
        int months = Math.toIntExact(experienceMetrics.totalMonths(candidateId));
        String province = Provinces.canonical(profile.location());
        var current = ExperienceMonths.current(periods).orElse(null);
        candidates.metrics(
                candidateId,
                new CatalogLinks.ProfileLink(
                        candidateId,
                        province,
                        months,
                        current == null ? null : current.companyId(),
                        current == null ? null : current.titleId()));

        var companies =
                catalog.companies(
                        experiences.stream()
                                .map(CatalogLinks.ExperienceLink::companyId)
                                .filter(Objects::nonNull)
                                .distinct()
                                .toList());
        var titles =
                catalog.titles(
                        experiences.stream()
                                .map(CatalogLinks.ExperienceLink::titleId)
                                .filter(Objects::nonNull)
                                .distinct()
                                .toList());
        var text = new ArrayList<String>();
        addText(text, profile.fullName());
        var companyIds = new TreeSet<Long>();
        var parents = new TreeSet<Long>();
        var titleIds = new TreeSet<Long>();
        var functions = new TreeSet<String>();
        var seniorities = new TreeSet<String>();
        var industries = new TreeSet<String>();
        var scopes = new TreeSet<String>();
        boolean ai = false;
        for (var experience : experiences) {
            var title = experience.titleId() == null ? null : titles.get(experience.titleId());
            var company =
                    experience.companyId() == null ? null : companies.get(experience.companyId());
            addText(text, experience.titleRaw());
            if (title != null) {
                addText(text, title.name());
                titleIds.add(title.id());
                functions.addAll(title.functionCodes());
                add(seniorities, title.seniorityCode());
                add(
                        scopes,
                        experience.scopeCode() == null
                                ? title.scopeCode()
                                : experience.scopeCode());
                ai |= "AI".equals(title.origin());
            }
            addText(text, experience.companyRaw());
            if (company != null) {
                addText(text, company.name());
                addText(text, company.parentName());
                companyIds.add(company.id());
                add(parents, company.parentId());
                add(industries, company.industryCode());
                add(scopes, experience.scopeCode());
                ai |= "AI".equals(company.origin());
            }
            ai |= "AI".equals(experience.normalizationOrigin());
        }
        addText(text, profile.location());
        addText(text, profile.summary());
        repository.upsert(
                new IndexSnapshot(
                        candidateId,
                        CatalogText.fold(String.join(" ", text)),
                        List.copyOf(companyIds),
                        List.copyOf(parents),
                        List.copyOf(titleIds),
                        List.copyOf(functions),
                        List.copyOf(seniorities),
                        List.copyOf(industries),
                        List.copyOf(scopes),
                        province,
                        months,
                        ai));
    }

    private static <T> void add(Set<T> values, T value) {
        if (value != null) values.add(value);
    }

    private static void addText(List<String> values, String value) {
        if (value != null && !value.isBlank()) values.add(value);
    }
}
