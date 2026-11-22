package com.cvcompass.backend.catalog;

import com.cvcompass.backend.catalog.repository.TaxonomyRepository;
import java.io.IOException;
import java.util.Map;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;

@Service
public class TaxonomyCodes {
    private final TaxonomyRepository repository;
    private final ObjectMapper json;

    public TaxonomyCodes(TaxonomyRepository repository, ObjectMapper json) {
        this.repository = repository;
        this.json = json;
    }

    @EventListener(ApplicationReadyEvent.class)
    @Transactional(rollbackFor = IOException.class)
    public void load() throws IOException {
        try (var stream = new ClassPathResource("catalog/taxonomy-v0.json").getInputStream()) {
            var document = json.readTree(stream);
            for (var kind :
                    Map.of(
                                    "FUNCTION",
                                    "functions",
                                    "SENIORITY",
                                    "seniority",
                                    "INDUSTRY",
                                    "industries",
                                    "SCOPE",
                                    "scopes")
                            .entrySet()) {
                var entries = document.path(kind.getValue());
                var codes =
                        entries.properties().stream()
                                .filter(entry -> !entry.getKey().equals("null"))
                                .toList();
                if (codes.isEmpty()) throw new IllegalStateException("TAXONOMY_RESOURCE_INVALID");
                repository.retireMissing(
                        kind.getKey(),
                        codes.stream().map(Map.Entry::getKey).toArray(String[]::new));
                for (var entry : codes) {
                    repository.upsert(kind.getKey(), entry.getKey(), entry.getValue().asString());
                }
            }
        }
    }

    public boolean isActive(String kind, String code) {
        return kind != null && code != null && repository.isActive(kind, code);
    }
}
