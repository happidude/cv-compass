package com.cvcompass.backend.ingestion.repository;

import com.cvcompass.backend.ingestion.entity.CvImportItem;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

public interface CvImportItemRepository extends JpaRepository<CvImportItem, Long> {
    Page<CvImportItem> findByBatchIdOrderByFileOrderAsc(long batchId, Pageable pageable);
}
