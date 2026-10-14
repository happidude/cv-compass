package com.cvcompass.backend.ingestion.repository;

import com.cvcompass.backend.ingestion.entity.CvDocument;
import org.springframework.data.jpa.repository.JpaRepository;

public interface CvDocumentRepository extends JpaRepository<CvDocument, Long> {
}
