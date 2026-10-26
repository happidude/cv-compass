package com.cvcompass.backend.ingestion.repository;

import com.cvcompass.backend.ingestion.entity.CvProcessingJob;
import org.springframework.data.jpa.repository.JpaRepository;

public interface CvProcessingJobRepository extends JpaRepository<CvProcessingJob, Long> {}
