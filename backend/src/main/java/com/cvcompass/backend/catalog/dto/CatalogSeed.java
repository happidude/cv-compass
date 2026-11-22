package com.cvcompass.backend.catalog.dto;

import java.util.List;

public record CatalogSeed(String schemaVersion, List<Company> companies, List<Title> titles) {
    public record Company(String name, String parent, String industry, List<String> aliases) {}

    public record Title(
            String name,
            List<String> functions,
            String seniority,
            String scope,
            List<String> aliases) {}
}
