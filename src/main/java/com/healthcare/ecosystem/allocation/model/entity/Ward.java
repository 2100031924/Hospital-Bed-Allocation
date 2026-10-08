package com.healthcare.ecosystem.allocation.model.entity;

import com.healthcare.ecosystem.allocation.model.enums.GenderPolicy;
import com.healthcare.ecosystem.allocation.model.enums.WardType;
import jakarta.persistence.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

@Entity
@Table(name = "wards")
public class Ward {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "hospital_id", nullable = false)
    private Hospital hospital;

    @Column(nullable = false, length = 100)
    private String name;

    @Enumerated(EnumType.STRING)
    @JdbcTypeCode(SqlTypes.VARCHAR)
    @Column(name = "ward_type", nullable = false, length = 50)
    private WardType wardType;

    @Enumerated(EnumType.STRING)
    @JdbcTypeCode(SqlTypes.VARCHAR)
    @Column(name = "gender_policy", nullable = false, length = 30)
    private GenderPolicy genderPolicy = GenderPolicy.UNISEX;

    @OneToMany(mappedBy = "ward", cascade = CascadeType.ALL, orphanRemoval = true)
    private List<Room> rooms = new ArrayList<>();

    @Column(name = "created_at", updatable = false)
    private Instant createdAt = Instant.now();

    @Column(name = "updated_at")
    private Instant updatedAt = Instant.now();

    public Ward() {}

    public Ward(Hospital hospital, String name, WardType wardType, GenderPolicy genderPolicy) {
        this.hospital = hospital;
        this.name = name;
        this.wardType = wardType;
        this.genderPolicy = genderPolicy == null ? GenderPolicy.UNISEX : genderPolicy;
    }

    @PreUpdate
    public void preUpdate() {
        this.updatedAt = Instant.now();
    }

    public Long getId() { return id; }
    public Hospital getHospital() { return hospital; }
    public void setHospital(Hospital hospital) { this.hospital = hospital; }
    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
    public WardType getWardType() { return wardType; }
    public void setWardType(WardType wardType) { this.wardType = wardType; }
    public GenderPolicy getGenderPolicy() { return genderPolicy; }
    public void setGenderPolicy(GenderPolicy genderPolicy) { this.genderPolicy = genderPolicy; }
    public List<Room> getRooms() { return rooms; }
    public void setRooms(List<Room> rooms) { this.rooms = rooms; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }
}