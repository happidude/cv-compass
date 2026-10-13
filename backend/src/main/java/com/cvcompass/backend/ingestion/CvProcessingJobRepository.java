package com.cvcompass.backend.ingestion;

import org.springframework.data.jpa.repository.JpaRepository;

public interface CvProcessingJobRepository extends JpaRepository<CvProcessingJob, Long> {
}
