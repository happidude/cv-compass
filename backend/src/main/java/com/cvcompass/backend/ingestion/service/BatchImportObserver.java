package com.cvcompass.backend.ingestion.service;

import com.cvcompass.backend.ingestion.dto.BatchItem;

/** Internal callbacks: started has no enclosing transaction; recorded joins the item transaction. */
public interface BatchImportObserver {
    default void created(long batchId) {}

    default void storageFailed(long bytes) {}

    void started(int order, long batchId);

    void recorded(int order, BatchItem result);
}
