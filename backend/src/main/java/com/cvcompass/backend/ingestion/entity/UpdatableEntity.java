package com.cvcompass.backend.ingestion.entity;

import jakarta.persistence.Column;
import jakarta.persistence.MappedSuperclass;
import lombok.Getter;
import lombok.Setter;

import java.time.Instant;

/** Extends the shared mapping only for tables with an application-maintained UPDATED_AT. */
@Getter
@Setter
@MappedSuperclass
public abstract class UpdatableEntity extends BaseEntity {
    @Column(name = "\"UPDATED_AT\"", nullable = false)
    private Instant updatedAt = Instant.now();

    protected UpdatableEntity() {
    }
}
