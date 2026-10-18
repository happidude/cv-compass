package com.cvcompass.backend.ingestion.entity;

import jakarta.persistence.*;
import lombok.*;

@Getter @Setter @NoArgsConstructor
@Entity @Table(name = "\"CV_IMPORT_ITEMS\"")
public class CvImportItem extends BaseEntity {
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "\"BATCH_ID\"", nullable = false)
    private CvImportBatch batch;
    @Column(name = "\"FILE_ORDER\"", nullable = false)
    private Integer fileOrder;
    @Column(name = "\"DISPLAY_NAME\"", nullable = false, length = 255)
    private String displayName;
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "\"DOCUMENT_ID\"")
    private CvDocument document;
    @Column(name = "\"ERROR_CODE\"", length = 40)
    private String errorCode;
}
