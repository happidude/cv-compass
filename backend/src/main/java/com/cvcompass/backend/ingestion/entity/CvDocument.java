package com.cvcompass.backend.ingestion.entity;

import com.cvcompass.backend.ingestion.enums.DocumentStatus;
import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import java.time.Instant;
import org.hibernate.annotations.Generated;
import org.hibernate.generator.EventType;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

@Getter
@Setter
@NoArgsConstructor
@Entity
@Table(name = "\"CV_DOCUMENTS\"")
public class CvDocument {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "\"ID\"")
    @Setter(AccessLevel.NONE)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "\"BATCH_ID\"", nullable = false)
    private CvImportBatch batch;

    @ManyToOne(fetch = FetchType.LAZY, optional = true)
    @JoinColumn(name = "\"CANDIDATE_ID\"", nullable = true)
    private Candidate candidate;

    @Column(name = "\"ORIGINAL_FILENAME\"", nullable = false, length = 255)
    private String originalFilename;

    @Column(name = "\"STORAGE_PATH\"", nullable = false, columnDefinition = "text")
    private String storagePath;

    @Column(name = "\"FILE_SIZE\"", nullable = false)
    private Long fileSize;

    @Column(name = "\"MIME_TYPE\"", nullable = false, length = 100)
    private String mimeType;

    @JdbcTypeCode(SqlTypes.CHAR)
    @Column(name = "\"SHA256\"", nullable = false, length = 64, columnDefinition = "char(64)")
    private String sha256;

    @Enumerated(EnumType.STRING)
    @Column(name = "\"STATUS\"", nullable = false, length = 20)
    private DocumentStatus status;

    @Generated(event = EventType.INSERT)
    @Column(name = "\"CREATED_AT\"", nullable = false, insertable = false, updatable = false)
    @Setter(AccessLevel.NONE)
    private Instant createdAt;
}
