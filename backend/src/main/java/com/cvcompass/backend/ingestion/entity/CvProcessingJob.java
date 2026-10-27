package com.cvcompass.backend.ingestion.entity;

import com.cvcompass.backend.ingestion.enums.JobStatus;
import com.cvcompass.backend.ingestion.enums.OcrEndReason;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import java.time.Instant;
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

    @Column(name = "\"PROFILE_REVISION\"", length = 128)
    private String profileRevision;

    @Column(name = "\"GENERATION\"")
    private Integer generation;

    @Column(name = "\"MAX_ATTEMPTS\"")
    private Integer maxAttempts;

    @Column(name = "\"ATTEMPTS_STARTED\"")
    private Integer attemptsStarted;

    @Column(name = "\"NEXT_ATTEMPT_AT\"")
    private Instant nextAttemptAt;

    @Column(name = "\"CURRENT_ATTEMPT_ID\"")
    private Long currentAttemptId;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "\"ADMISSION_SNAPSHOT\"", columnDefinition = "jsonb")
    private Map<String, Object> admissionSnapshot;

    @Enumerated(EnumType.STRING)
    @Column(name = "\"END_REASON\"", length = 40)
    private OcrEndReason endReason;

    @Column(name = "\"ERROR_CODE\"", length = 60)
    private String errorCode;
}
