package com.cvcompass.backend.catalog.service;

import com.cvcompass.backend.catalog.Fold;
import com.cvcompass.backend.catalog.dto.CatalogSeed;
import com.cvcompass.backend.catalog.dto.ImportCounts;
import com.cvcompass.backend.catalog.exception.CatalogFailure;
import com.cvcompass.backend.catalog.repository.CatalogRepository;
import com.cvcompass.backend.web.FieldViolation;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class CatalogImportService {
    private final CatalogSeedValidator validator;
    private final CatalogRepository repository;

    public CatalogImportService(CatalogSeedValidator validator, CatalogRepository repository) {
        this.validator = validator;
        this.repository = repository;
    }

    @Transactional
    public ImportCounts importSeed(CatalogSeed seed) {
        repository.lockImportTables();
        validator.validate(seed);
        var names = new LinkedHashMap<String, String>();
        var entries = new LinkedHashMap<String, CatalogSeed.Company>();
        var paths = new HashMap<String, String>();
        for (int i = 0; i < seed.companies().size(); i++) {
            var company = seed.companies().get(i);
            String key = Fold.fold(company.name());
            names.put(key, company.name());
            entries.put(key, company);
            paths.put(key, "companies[" + i + "]");
            if (company.parent() != null) {
                names.putIfAbsent(Fold.fold(company.parent()), company.parent());
                paths.putIfAbsent(Fold.fold(company.parent()), "companies[" + i + "]");
            }
        }
        var keys = new LinkedHashMap<String, CatalogRepository.CompanyKey>();
        var counts = new Counts();
        var explicitIds = new HashSet<Long>();
        for (var entry : entries.entrySet()) {
            var company = entry.getValue();
            String path = paths.get(entry.getKey());
            var key = repository.ensureCompany(company.name(), path + ".name", company.aliases());
            if (!explicitIds.add(key.id())) throw duplicate(path + ".name");
            keys.put(entry.getKey(), key);
            for (var alias : aliases(company.name(), company.aliases(), path).entrySet())
                counts.add(
                        repository.upsertCompanyAlias(key.id(), alias.getKey(), alias.getValue()));
        }
        // Explicit aliases must exist before resolving parent references by those aliases.
        for (var name : names.entrySet()) {
            if (keys.containsKey(name.getKey())) continue;
            String path = paths.get(name.getKey()) + ".parent";
            var key = repository.ensureCompany(name.getValue(), path);
            keys.put(name.getKey(), key);
            counts.add(repository.upsertCompanyAlias(key.id(), name.getValue(), path));
        }
        var updatedIds = new HashSet<Long>();
        for (var entry : entries.entrySet()) {
            var company = entry.getValue();
            var key = keys.get(entry.getKey());
            Long parent =
                    company.parent() == null ? null : keys.get(Fold.fold(company.parent())).id();
            if (parent != null && parent == key.id())
                throw cycle(paths.get(entry.getKey()) + ".parent");
            if (repository.updateCompany(key.id(), company, parent)) updatedIds.add(key.id());
        }
        var cyclicIds = repository.cyclicCompanies(explicitIds.toArray(Long[]::new));
        for (var entry : entries.entrySet()) {
            if (cyclicIds.contains(keys.get(entry.getKey()).id()))
                throw cycle(paths.get(entry.getKey()) + ".parent");
        }
        var countedIds = new HashSet<Long>();
        for (var key : keys.values()) {
            if (countedIds.add(key.id())) counts.add(key.created(), updatedIds.contains(key.id()));
        }
        var titleIds = new HashSet<Long>();
        for (int i = 0; i < seed.titles().size(); i++) {
            var title = seed.titles().get(i);
            String path = "titles[" + i + "]";
            var key = repository.ensureTitle(title, path + ".name");
            if (!titleIds.add(key.id())) throw duplicate(path + ".name");
            for (var alias : aliases(title.name(), title.aliases(), path).entrySet())
                counts.add(repository.upsertTitleAlias(key.id(), alias.getKey(), alias.getValue()));
            counts.add(key.created(), repository.updateTitle(key.id(), title));
        }
        return new ImportCounts(counts.created, counts.updated, counts.unchanged);
    }

    private static LinkedHashMap<String, String> aliases(
            String name, List<String> aliases, String path) {
        var unique = new LinkedHashMap<String, String>();
        var values = new LinkedHashMap<String, String>();
        unique.put(Fold.fold(name), name);
        values.put(name, path + ".name");
        if (aliases != null)
            for (int i = 0; i < aliases.size(); i++) {
                String alias = aliases.get(i);
                if (unique.putIfAbsent(Fold.fold(alias), alias) == null)
                    values.put(alias, path + ".aliases[" + i + "]");
            }
        return values;
    }

    private static CatalogFailure cycle(String path) {
        return new CatalogFailure(
                List.of(new FieldViolation(path, "Company parent chain contains a cycle")));
    }

    private static CatalogFailure duplicate(String path) {
        return new CatalogFailure(
                List.of(
                        new FieldViolation(
                                path, "Multiple entries resolve to the same catalog entry")));
    }

    private static final class Counts {
        int created;
        int updated;
        int unchanged;

        void add(boolean inserted, boolean changed) {
            if (inserted) created++;
            else if (changed) updated++;
            else unchanged++;
        }

        void add(List<Boolean> result) {
            add(!result.isEmpty() && result.getFirst(), !result.isEmpty());
        }
    }
}
