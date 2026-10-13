package com.cvcompass.backend.ingestion;

import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import java.time.Instant;
import org.hibernate.annotations.Generated;
import org.hibernate.generator.EventType;

@Getter
@Setter
@NoArgsConstructor
@Entity
@Table(name = "\"CV_PROCESSING_JOBS\"")
public class CvProcessingJob {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "\"ID\"")
    @Setter(AccessLevel.NONE)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "\"CV_DOCUMENT_ID\"", nullable = false)
    private CvDocument document;

    @Enumerated(EnumType.STRING)
    @Column(name = "\"STATUS\"", nullable = false, length = 20)
    private JobStatus status;

    @Column(name = "\"RETRY_COUNT\"", nullable = false)
    private Integer retryCount = 0;

    @Column(name = "\"ERROR_MESSAGE\"", columnDefinition = "text")
    private String errorMessage;

    @Column(name = "\"STARTED_AT\"")
    private Instant startedAt;

    @Column(name = "\"COMPLETED_AT\"")
    private Instant completedAt;

    @Generated(event = EventType.INSERT)
    @Column(name = "\"CREATED_AT\"", nullable = false, insertable = false, updatable = false)
    @Setter(AccessLevel.NONE)
    private Instant createdAt;
}
