package com.cvcompass.backend.mapping.service;

import org.springframework.dao.RecoverableDataAccessException;
import org.springframework.dao.TransientDataAccessException;
import org.springframework.stereotype.Service;

/** Local CPU-only worker; no network API, external AI or candidate writes. */
@Service
public class MappingWorker {
    private final MappingService mapping;

    public MappingWorker(MappingService mapping) {
        this.mapping = mapping;
    }

    public boolean runOne() {
        var next = mapping.claim();
        if (next.isEmpty()) return false;
        var claim = next.orElseThrow();
        try {
            mapping.complete(claim, mapping.process(claim));
        } catch (RuntimeException failure) {
            // Only a fixed operational code is persisted; no input/exception message is logged.
            try {
                mapping.fail(
                        claim,
                        failure instanceof TransientDataAccessException
                                || failure instanceof RecoverableDataAccessException);
            } catch (IllegalStateException stale) {
                if (!"MAPPING_STALE_ATTEMPT".equals(stale.getMessage())) throw stale;
            }
        }
        return true;
    }
}
