package com.cvcompass.backend.ingestion.entity;

import com.cvcompass.backend.ingestion.enums.JobStatus;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import java.time.Instant;

@Getter
@Setter
@NoArgsConstructor
@Entity
@Table(name = "\"CV_PROCESSING_JOBS\"")
public class CvProcessingJob extends BaseEntity {

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

}
