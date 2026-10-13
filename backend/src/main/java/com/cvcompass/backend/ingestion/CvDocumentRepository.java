package com.cvcompass.backend.ingestion;

import org.springframework.data.jpa.repository.JpaRepository;

public interface CvDocumentRepository extends JpaRepository<CvDocument, Long> {
}
