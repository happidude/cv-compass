package com.cvcompass.backend.ingestion;

import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import java.time.Instant;
import org.hibernate.annotations.Generated;
import org.hibernate.generator.EventType;
import java.util.Map;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

@Getter
@Setter
@NoArgsConstructor
@Entity
@Table(name = "\"CV_EXTRACTIONS\"")
public class CvExtraction {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "\"ID\"")
    @Setter(AccessLevel.NONE)
    private Long id;

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

    @Generated(event = EventType.INSERT)
    @Column(name = "\"CREATED_AT\"", nullable = false, insertable = false, updatable = false)
    @Setter(AccessLevel.NONE)
    private Instant createdAt;
}
