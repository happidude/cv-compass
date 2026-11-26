package com.cvcompass.backend.search.service;

import com.cvcompass.backend.catalog.api.CatalogLookup;
import com.cvcompass.backend.catalog.api.CatalogText;
import com.cvcompass.backend.search.config.SearchProperties;
import com.cvcompass.backend.search.dto.CandidateSearchPage;
import com.cvcompass.backend.search.dto.CandidateSearchPage.Company;
import com.cvcompass.backend.search.dto.CandidateSearchPage.CompanySuggestion;
import com.cvcompass.backend.search.dto.CandidateSearchPage.MatchedCompany;
import com.cvcompass.backend.search.dto.CandidateSearchPage.Result;
import com.cvcompass.backend.search.dto.CandidateSearchPage.Title;
import com.cvcompass.backend.search.dto.CandidateSearchRequest;
import com.cvcompass.backend.search.dto.SearchCursor;
import com.cvcompass.backend.search.exception.SearchFailure;
import com.cvcompass.backend.search.repository.CandidateSearchRepository;
import com.cvcompass.backend.search.repository.CandidateSearchRepository.Criteria;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Transactional(readOnly = true)
public class CandidateSearchService {
    private final CandidateSearchRepository repository;
    private final CatalogLookup catalog;
    private final SearchProperties properties;

    public CandidateSearchService(
            CandidateSearchRepository repository,
            CatalogLookup catalog,
            SearchProperties properties) {
        this.repository = repository;
        this.catalog = catalog;
        this.properties = properties;
    }

    public CandidateSearchPage search(CandidateSearchRequest request) {
        var config = properties.getQuery();
        Integer requestedLimit = integer(request.limit(), "limit");
        int limit = requestedLimit == null ? config.getDefaultLimit() : requestedLimit;
        if (limit < 1 || limit > config.getMaxLimit())
            throw new SearchFailure("limit", "Limit is outside the configured range");
        Integer min = integer(request.minYears(), "minYears");
        Integer max = integer(request.maxYears(), "maxYears");
        if (min != null && max != null && min > max)
            throw new SearchFailure("maxYears", "Must be greater than or equal to minYears");
        var cursor = SearchCursor.decode(request.cursor());
        Map<String, List<String>> codes = new LinkedHashMap<>();
        codes.put("FUNCTION", request.function());
        codes.put("SENIORITY", request.seniority());
        codes.put("SCOPE", request.scope());
        codes.put("INDUSTRY", request.industry());
        var activeCodes =
                repository.activeCodes(
                        codes.entrySet().stream()
                                .filter(group -> !group.getValue().isEmpty())
                                .map(Map.Entry::getKey)
                                .toList());
        for (var group : codes.entrySet()) {
            if (!group.getValue().isEmpty()
                    && !activeCodes
                            .getOrDefault(group.getKey(), List.of())
                            .containsAll(group.getValue())) {
                throw new SearchFailure(
                        group.getKey().toLowerCase(Locale.ROOT),
                        "Must contain active codes of the requested kind");
            }
        }
        String company = fold(request.company());
        String title = fold(request.title());
        List<Long> companyIds =
                request.companyId() == null
                        ? resolve("COMPANY", company)
                        : List.of(request.companyId());
        List<Long> titleIds = resolve("TITLE", title);
        var rows =
                repository.search(
                        new Criteria(
                                fold(request.q()),
                                company,
                                textFilter(title),
                                request.companyId() != null || !company.isEmpty(),
                                !title.isEmpty(),
                                companyIds,
                                titleIds,
                                codes,
                                request.province(),
                                min,
                                max,
                                cursor,
                                limit),
                        config);
        boolean hasNext = rows.size() > limit;
        var page = rows.subList(0, Math.min(rows.size(), limit));
        var companyKeys = new HashSet<>(companyIds);
        page.stream()
                .map(CandidateSearchRepository.Row::companyId)
                .filter(Objects::nonNull)
                .forEach(companyKeys::add);
        var companies = catalog.companies(companyKeys);
        var titles =
                catalog.titles(
                        page.stream()
                                .map(CandidateSearchRepository.Row::titleId)
                                .filter(Objects::nonNull)
                                .toList());
        var content =
                page.stream()
                        .map(
                                row -> {
                                    var currentCompany =
                                            row.companyId() == null
                                                    ? null
                                                    : companies.get(row.companyId());
                                    var currentTitle =
                                            row.titleId() == null
                                                    ? null
                                                    : titles.get(row.titleId());
                                    Long matchedId =
                                            companyIds.stream()
                                                    .filter(row.companyIds()::contains)
                                                    .findFirst()
                                                    .orElseGet(
                                                            () ->
                                                                    companyIds.stream()
                                                                            .filter(
                                                                                    row
                                                                                                    .parentCompanyIds()
                                                                                            ::contains)
                                                                            .findFirst()
                                                                            .orElse(null));
                                    var matched =
                                            matchedId == null ? null : companies.get(matchedId);
                                    return new Result(
                                            row.id(),
                                            row.fullName(),
                                            currentTitle == null
                                                    ? null
                                                    : new Title(
                                                            currentTitle.id(),
                                                            currentTitle.name(),
                                                            "AI".equals(currentTitle.origin())),
                                            currentCompany == null
                                                    ? null
                                                    : new Company(
                                                            currentCompany.id(),
                                                            currentCompany.name(),
                                                            currentCompany.parentName(),
                                                            "AI".equals(currentCompany.origin())),
                                            row.province(),
                                            row.months(),
                                            true,
                                            row.score(),
                                            row.reasons(),
                                            matched == null
                                                    ? null
                                                    : new MatchedCompany(
                                                            matched.id(), matched.name()));
                                })
                        .toList();
        String nextCursor =
                hasNext
                        ? new SearchCursor(page.getLast().score(), page.getLast().id()).encode()
                        : null;
        return new CandidateSearchPage(content, nextCursor, hasNext);
    }

    public Map<String, Object> options() {
        return repository.options();
    }

    public List<CompanySuggestion> companies(String q) {
        String folded = fold(q);
        return folded.isEmpty() ? List.of() : repository.companies(folded, properties.getQuery());
    }

    private List<Long> resolve(String kind, String value) {
        if (value.isEmpty()) return List.of();
        if ("TITLE".equals(kind) && value.matches("[0-9]+")) {
            try {
                return List.of(Long.parseLong(value));
            } catch (NumberFormatException failure) {
                throw new SearchFailure(kind.toLowerCase(Locale.ROOT), "Invalid catalog id");
            }
        }
        return repository.resolve(kind, value, properties.getQuery());
    }

    private static String textFilter(String value) {
        return value.matches("[0-9]+") ? "" : value;
    }

    private static String fold(String value) {
        return value == null ? "" : CatalogText.fold(value);
    }

    private static Integer integer(String value, String field) {
        if (value == null) return null;
        try {
            int number = Integer.parseInt(value);
            if (number < 0) throw new NumberFormatException();
            return number;
        } catch (NumberFormatException failure) {
            throw new SearchFailure(field, "Must be a non-negative integer");
        }
    }
}
