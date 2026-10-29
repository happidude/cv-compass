package com.cvcompass.backend.candidate.service;

import com.cvcompass.backend.candidate.repository.PublicationRepository;
import com.cvcompass.backend.mapping.api.CompletedMappings;
import java.util.List;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class PublicationSweep {
    private final PublicationRepository repository;
    private final CompletedMappings mappings;

    public PublicationSweep(PublicationRepository repository, CompletedMappings mappings) {
        this.repository = repository;
        this.mappings = mappings;
    }

    /** Reserve bounded metadata only. Crash/failure is recovered by the next bounded reconciliation sweep. */
    @Transactional
    public List<Long> reserve(int size) {
        var state = repository.lockCursor();
        long after = state.afterId(),
                upper = state.upperId() == null ? mappings.upperId() : state.upperId();
        var ids = mappings.scan(after, upper, size);
        boolean done = ids.size() < size || !ids.isEmpty() && ids.getLast() == upper;
        repository.cursor(done ? 0 : ids.getLast(), done ? null : upper);
        return ids;
    }
}
