package com.cvcompass.backend.ingestion;

import org.springframework.data.jpa.repository.JpaRepository;

public interface CvExtractionRepository extends JpaRepository<CvExtraction, Long> {
}
