package com.cvcompass.backend.ingestion;

import org.springframework.data.jpa.repository.JpaRepository;

public interface CvImportBatchRepository extends JpaRepository<CvImportBatch, Long> {
}
