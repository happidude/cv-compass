package com.cvcompass.backend.ingestion.repository;

import com.cvcompass.backend.ingestion.entity.CvImportBatch;
import org.springframework.data.jpa.repository.JpaRepository;

public interface CvImportBatchRepository extends JpaRepository<CvImportBatch, Long> {}
