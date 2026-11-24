package com.cvcompass.backend.catalog.api;

import java.util.Collection;
import java.util.List;
import java.util.Map;

/** Read-only snapshots of explicitly linked entries; never resolves or creates identities. */
public interface CatalogLookup {
    Map<Long, Company> companies(Collection<Long> ids);

    Map<Long, Title> titles(Collection<Long> ids);

    record Company(
            long id,
            String name,
            Long parentId,
            String parentName,
            String industryCode,
            String origin) {}

    record Title(
            long id,
            String name,
            List<String> functionCodes,
            String seniorityCode,
            String scopeCode,
            String origin) {}
}
