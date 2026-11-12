package com.cvcompass.backend.mapping.service;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

final class MappingProviderRegistry {
    private final Map<String, MappingProvider> providers;

    MappingProviderRegistry(List<MappingProvider> providers) {
        if (providers == null) throw new IllegalStateException("Mapping providers required");
        var registered = new HashMap<String, MappingProvider>();
        for (var provider : providers) {
            if (provider == null || provider.engineVersion() == null) {
                throw new IllegalStateException("Mapping provider version required");
            }
            if (registered.putIfAbsent(provider.engineVersion(), provider) != null) {
                throw new IllegalStateException("Duplicate mapping engine version");
            }
        }
        this.providers = Map.copyOf(registered);
    }

    MappingProvider require(String version) {
        var provider = version == null ? null : providers.get(version);
        if (provider == null) throw new IllegalArgumentException("Unsupported mapping engine");
        return provider;
    }

    Set<String> versions() {
        return providers.keySet();
    }
}
