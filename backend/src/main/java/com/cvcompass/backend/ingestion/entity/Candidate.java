package com.cvcompass.backend.ingestion.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Getter
@Setter
@NoArgsConstructor
@Entity
@Table(schema = "\"CANDIDATE\"", name = "\"CANDIDATES\"")
public class Candidate extends UpdatableEntity {

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
}
