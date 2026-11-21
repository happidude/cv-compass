package com.cvcompass.backend.ingestion.entity;

import com.cvcompass.backend.ingestion.enums.DocumentStatus;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

@Getter
@Setter
@NoArgsConstructor
@Entity
@Table(schema = "\"INGESTION\"", name = "\"DOCUMENTS\"")
public class CvDocument extends BaseEntity {

    @ManyToOne(fetch = FetchType.LAZY, optional = true)
    @JoinColumn(name = "\"BATCH_ID\"", nullable = true)
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
}
