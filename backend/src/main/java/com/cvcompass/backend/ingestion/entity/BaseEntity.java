package com.cvcompass.backend.ingestion.entity;

import jakarta.persistence.Column;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.MappedSuperclass;
import java.time.Instant;
import lombok.Getter;
import org.hibernate.annotations.Generated;
import org.hibernate.generator.EventType;

/** Shared mapping for tables with an identity ID and database-generated creation time. */
@Getter
@MappedSuperclass
public abstract class BaseEntity {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "\"ID\"")
    private Long id;

    @Generated(event = EventType.INSERT)
    @Column(name = "\"CREATED_AT\"", nullable = false, insertable = false, updatable = false)
    private Instant createdAt;

    protected BaseEntity() {}
}
