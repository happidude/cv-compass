package com.cvcompass.backend.catalog.service;

import com.cvcompass.backend.catalog.exception.AliasRequeueFailure;
import com.cvcompass.backend.catalog.repository.NormalizationTaskRepository;
import java.util.Set;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class AliasRequeueService {
    private final NormalizationTaskRepository repository;

    public AliasRequeueService(NormalizationTaskRepository repository) {
        this.repository = repository;
    }

    @Transactional
    public int requeue(String kind, String errorCode) {
        if (kind != null && !Set.of("COMPANY", "TITLE").contains(kind))
            throw new AliasRequeueFailure();
        return repository.requeue(kind, errorCode);
    }
}
