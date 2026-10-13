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
@Table(name = "\"CANDIDATES\"")
public class Candidate {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "\"ID\"")
    @Setter(AccessLevel.NONE)
    private Long id;

    @Column(name = "\"FULL_NAME\"", length = 255)
    private String fullName;

    @Column(name = "\"EMAIL\"", length = 255)
    private String email;

    @Column(name = "\"PHONE\"", length = 50)
    private String phone;

    @Column(name = "\"LOCATION\"", length = 255)
    private String location;

    @Column(name = "\"SUMMARY\"", columnDefinition = "text")
    private String summary;

    @Column(name = "\"UPDATED_AT\"", nullable = false)
    private Instant updatedAt = Instant.now();

    @Generated(event = EventType.INSERT)
    @Column(name = "\"CREATED_AT\"", nullable = false, insertable = false, updatable = false)
    @Setter(AccessLevel.NONE)
    private Instant createdAt;
}
