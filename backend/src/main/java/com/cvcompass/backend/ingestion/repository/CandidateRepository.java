package com.cvcompass.backend.ingestion.repository;

import com.cvcompass.backend.ingestion.entity.Candidate;
import org.springframework.data.jpa.repository.JpaRepository;

public interface CandidateRepository extends JpaRepository<Candidate, Long> {
}
