package com.cvcompass.backend.ingestion.entity;

import com.cvcompass.backend.ingestion.enums.*;
import jakarta.persistence.*;
import java.time.Instant;
import java.util.UUID;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

@Getter
@Setter
@NoArgsConstructor
@Entity
@Table(name = "\"CV_OCR_ATTEMPTS\"")
public class CvOcrAttempt extends BaseEntity {
    @Column(name = "\"PUBLIC_ID\"", nullable = false)
    private UUID publicId;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "\"JOB_ID\"", nullable = false)
    private CvProcessingJob job;

    @Column(name = "\"ATTEMPT_NO\"", nullable = false)
    private Integer attemptNo;

    @Column(name = "\"WORKER_ID\"", nullable = false, length = 128)
    private String workerId;

    @Column(name = "\"CLAIM_REQUEST_ID\"", nullable = false)
    private UUID claimRequestId;

    @JdbcTypeCode(SqlTypes.CHAR)
    @Column(
            name = "\"CLAIM_BODY_SHA256\"",
            nullable = false,
            length = 64,
            columnDefinition = "char(64)")
    private String claimBodySha256;

    @JdbcTypeCode(SqlTypes.CHAR)
    @Column(
            name = "\"LEASE_TOKEN_HASH\"",
            nullable = false,
            length = 64,
            columnDefinition = "char(64)")
    private String leaseTokenHash;

    @Column(name = "\"LEASE_EXPIRES_AT\"", nullable = false)
    private Instant leaseExpiresAt;

    @Column(name = "\"HARD_DEADLINE_AT\"", nullable = false)
    private Instant hardDeadlineAt;

    @Column(name = "\"RECLAIM_AFTER\"", nullable = false)
    private Instant reclaimAfter;

    @Enumerated(EnumType.STRING)
    @Column(name = "\"STATUS\"", nullable = false, length = 20)
    private OcrAttemptStatus status;

    @Column(name = "\"STARTED_AT\"", nullable = false)
    private Instant startedAt;

    @Column(name = "\"FINISHED_AT\"")
    private Instant finishedAt;

    @Enumerated(EnumType.STRING)
    @Column(name = "\"END_REASON\"", length = 40)
    private OcrEndReason endReason;

    @Column(name = "\"ERROR_CODE\"", length = 60)
    private String errorCode;

    @JdbcTypeCode(SqlTypes.CHAR)
    @Column(name = "\"OUTCOME_SHA256\"", length = 64, columnDefinition = "char(64)")
    private String outcomeSha256;
}
