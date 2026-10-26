package com.cvcompass.backend.ingestion.entity;

import com.cvcompass.backend.ingestion.enums.ExtractionMethod;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import java.util.Map;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

@Getter
@Setter
@NoArgsConstructor
@Entity
@Table(name = "\"CV_EXTRACTIONS\"")
public class CvExtraction extends BaseEntity {

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "\"CV_DOCUMENT_ID\"", nullable = false)
    private CvDocument document;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "\"PROCESSING_JOB_ID\"", nullable = false)
    private CvProcessingJob processingJob;

    @Column(name = "\"RAW_TEXT\"", columnDefinition = "text")
    private String rawText;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "\"STRUCTURED_DATA\"", columnDefinition = "jsonb")
    private Map<String, Object> structuredData;

    @Enumerated(EnumType.STRING)
    @Column(name = "\"EXTRACTION_METHOD\"", nullable = false, length = 30)
    private ExtractionMethod extractionMethod;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "\"ATTEMPT_ID\"")
    private CvOcrAttempt attempt;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "\"OCR_RESULT\"", columnDefinition = "jsonb")
    private Map<String, Object> ocrResult;

    @Column(name = "\"OCR_STATUS\"", length = 20)
    private String ocrStatus;

    @Column(name = "\"PROFILE_REVISION\"", length = 128)
    private String profileRevision;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "\"PROVENANCE\"", columnDefinition = "jsonb")
    private Map<String, Object> provenance;
}
