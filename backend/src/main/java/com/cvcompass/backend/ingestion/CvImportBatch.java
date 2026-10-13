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
@Table(name = "\"CV_IMPORT_BATCHES\"")
public class CvImportBatch {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "\"ID\"")
    @Setter(AccessLevel.NONE)
    private Long id;

    @Column(name = "\"TOTAL_FILES\"", nullable = false)
    private Integer totalFiles;

    @Column(name = "\"SUCCESS_COUNT\"", nullable = false)
    private Integer successCount = 0;

    @Column(name = "\"FAILED_COUNT\"", nullable = false)
    private Integer failedCount = 0;

    @Enumerated(EnumType.STRING)
    @Column(name = "\"STATUS\"", nullable = false, length = 20)
    private BatchStatus status;

    @Column(name = "\"COMPLETED_AT\"")
    private Instant completedAt;

    @Generated(event = EventType.INSERT)
    @Column(name = "\"CREATED_AT\"", nullable = false, insertable = false, updatable = false)
    @Setter(AccessLevel.NONE)
    private Instant createdAt;
}
