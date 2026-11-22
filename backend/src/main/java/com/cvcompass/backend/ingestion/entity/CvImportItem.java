package com.cvcompass.backend.ingestion.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Getter
@Setter
@NoArgsConstructor
@Entity
@Table(schema = "\"INGESTION\"", name = "\"IMPORT_ITEMS\"")
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
