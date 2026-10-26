package com.cvcompass.backend.ingestion.repository;

import com.cvcompass.backend.ingestion.entity.CvOcrAttempt;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface CvOcrAttemptRepository extends JpaRepository<CvOcrAttempt, Long> {
    List<CvOcrAttempt> findByJobIdOrderByAttemptNoAsc(Long jobId);
}
