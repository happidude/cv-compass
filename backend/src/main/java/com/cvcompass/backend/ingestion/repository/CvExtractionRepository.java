package com.cvcompass.backend.ingestion.repository;

import com.cvcompass.backend.ingestion.entity.CvExtraction;
import org.springframework.data.jpa.repository.JpaRepository;

public interface CvExtractionRepository extends JpaRepository<CvExtraction, Long> {
}
