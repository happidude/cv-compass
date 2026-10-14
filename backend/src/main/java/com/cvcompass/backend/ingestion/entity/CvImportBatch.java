package com.cvcompass.backend.ingestion.entity;

import com.cvcompass.backend.ingestion.enums.BatchStatus;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import java.time.Instant;

@Getter
@Setter
@NoArgsConstructor
@Entity
@Table(name = "\"CV_IMPORT_BATCHES\"")
public class CvImportBatch extends BaseEntity {

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

}
