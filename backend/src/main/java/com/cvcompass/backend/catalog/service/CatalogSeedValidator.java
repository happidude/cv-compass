package com.cvcompass.backend.catalog.service;

import com.cvcompass.backend.catalog.Fold;
import com.cvcompass.backend.catalog.TaxonomyCodes;
import com.cvcompass.backend.catalog.config.CatalogImportProperties;
import com.cvcompass.backend.catalog.dto.CatalogSeed;
import com.cvcompass.backend.catalog.exception.CatalogFailure;
import com.cvcompass.backend.catalog.repository.CatalogRepository;
import com.cvcompass.backend.web.FieldViolation;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.stereotype.Component;

@Component
public class CatalogSeedValidator {
    private final CatalogImportProperties properties;
    private final TaxonomyCodes taxonomy;
    private final CatalogRepository repository;

    public CatalogSeedValidator(
            TaxonomyCodes taxonomy,
            CatalogRepository repository,
            CatalogImportProperties properties) {
        this.taxonomy = taxonomy;
        this.repository = repository;
        this.properties = properties;
    }

    public void validate(CatalogSeed seed) {
        var errors = new ArrayList<FieldViolation>();
        if (seed == null)
            throw new CatalogFailure(
                    List.of(new FieldViolation("body", "Seed object is required")));
        if (!"catalog-seed/0".equals(seed.schemaVersion()))
            error(errors, "schemaVersion", "Expected catalog-seed/0");
        if (seed.companies() == null) error(errors, "companies", "Array is required");
        if (seed.titles() == null) error(errors, "titles", "Array is required");
        if (!errors.isEmpty()) throw new CatalogFailure(errors);
        if ((long) seed.companies().size() + seed.titles().size() > properties.getMaxEntries())
            throw tooLarge("body");
        for (int i = 0; i < seed.companies().size(); i++) {
            var company = seed.companies().get(i);
            if (company != null
                    && company.aliases() != null
                    && company.aliases().size() > properties.getMaxAliasesPerEntry())
                throw tooLarge("companies[" + i + "].aliases");
        }
        for (int i = 0; i < seed.titles().size(); i++) {
            var title = seed.titles().get(i);
            if (title != null
                    && title.aliases() != null
                    && title.aliases().size() > properties.getMaxAliasesPerEntry())
                throw tooLarge("titles[" + i + "].aliases");
        }
        var referencedParents = new HashSet<String>();
        for (var company : seed.companies()) {
            if (company != null && !blank(company.parent()))
                referencedParents.add(Fold.fold(company.parent()));
        }
        var companyNames = new HashSet<String>();
        var companyAliases = new HashMap<String, String>();
        if (seed.companies() != null)
            for (int i = 0; i < seed.companies().size(); i++) {
                var item = seed.companies().get(i);
                String path = "companies[" + i + "]";
                if (item == null) {
                    error(errors, path, "Entry is required");
                    continue;
                }
                name(item.name(), path + ".name", companyNames, errors);
                boolean parentOnly =
                        item.industry() == null
                                && !blank(item.name())
                                && (referencedParents.contains(Fold.fold(item.name()))
                                        || (item.aliases() != null
                                                && item.aliases().stream()
                                                        .anyMatch(
                                                                alias ->
                                                                        referencedParents.contains(
                                                                                Fold.fold(alias))))
                                        || repository.hasChildren(item.name(), path + ".industry"));
                if (!parentOnly) code("INDUSTRY", item.industry(), path + ".industry", errors);
                other(item.industry(), path + ".industry", errors);
                if (item.parent() != null) {
                    if (blank(item.parent()))
                        error(errors, path + ".parent", "Name must not be blank");
                    else if (Fold.fold(item.parent()).equals(Fold.fold(item.name())))
                        error(errors, path + ".parent", "Company cannot be its own parent");
                }
                aliases(item.name(), item.aliases(), path, companyAliases, errors);
            }
        var titleNames = new HashSet<String>();
        var titleAliases = new HashMap<String, String>();
        if (seed.titles() != null)
            for (int i = 0; i < seed.titles().size(); i++) {
                var item = seed.titles().get(i);
                String path = "titles[" + i + "]";
                if (item == null) {
                    error(errors, path, "Entry is required");
                    continue;
                }
                name(item.name(), path + ".name", titleNames, errors);
                if (item.functions() == null
                        || item.functions().isEmpty()
                        || item.functions().size() > 2)
                    error(errors, path + ".functions", "Expected one or two function codes");
                if (item.functions() != null)
                    for (int j = 0; j < item.functions().size(); j++) {
                        code(
                                "FUNCTION",
                                item.functions().get(j),
                                path + ".functions[" + j + "]",
                                errors);
                        other(item.functions().get(j), path + ".functions[" + j + "]", errors);
                    }
                code("SENIORITY", item.seniority(), path + ".seniority", errors);
                if (item.scope() != null) code("SCOPE", item.scope(), path + ".scope", errors);
                aliases(item.name(), item.aliases(), path, titleAliases, errors);
            }
        if (!errors.isEmpty()) throw new CatalogFailure(errors);
    }

    private void code(String kind, String value, String path, List<FieldViolation> errors) {
        if (!taxonomy.isActive(kind, value))
            error(errors, path, "Expected an active taxonomy code");
    }

    private static void other(String code, String path, List<FieldViolation> errors) {
        // The approved seed format has no proposed label; do not invent one from a name.
        if ("OTHER".equals(code))
            error(errors, path, "OTHER requires a proposed label unavailable in catalog-seed/0");
    }

    private static void name(
            String value, String path, Set<String> names, List<FieldViolation> errors) {
        if (blank(value)) error(errors, path, "Name must not be blank");
        else if (!names.add(Fold.fold(value))) error(errors, path, "Duplicate folded name");
    }

    private static void aliases(
            String name,
            List<String> aliases,
            String path,
            Map<String, String> owners,
            List<FieldViolation> errors) {
        String owner = Fold.fold(name);
        register(name, owner, path + ".name", owners, errors);
        if (aliases != null)
            for (int i = 0; i < aliases.size(); i++)
                register(aliases.get(i), owner, path + ".aliases[" + i + "]", owners, errors);
    }

    private static void register(
            String alias,
            String owner,
            String path,
            Map<String, String> owners,
            List<FieldViolation> errors) {
        if (blank(alias)) {
            error(errors, path, "Alias must not be blank");
            return;
        }
        String previous = owners.putIfAbsent(Fold.fold(alias), owner);
        if (previous != null && !previous.equals(owner))
            error(errors, path, "Alias belongs to multiple entries");
    }

    private static boolean blank(String value) {
        return value == null || Fold.fold(value).isEmpty();
    }

    private static void error(List<FieldViolation> errors, String path, String message) {
        errors.add(new FieldViolation(path, message));
    }

    private static CatalogFailure tooLarge(String path) {
        return new CatalogFailure(
                "SEED_TOO_LARGE", List.of(new FieldViolation(path, "SEED_TOO_LARGE")));
    }
}
