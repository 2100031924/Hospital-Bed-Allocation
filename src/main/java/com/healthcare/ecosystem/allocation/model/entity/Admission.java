package com.healthcare.ecosystem.allocation.model.entity;

import com.healthcare.ecosystem.allocation.model.enums.AdmissionPriority;
import com.healthcare.ecosystem.allocation.model.enums.AdmissionStatus;
import com.healthcare.ecosystem.allocation.model.enums.BedType;
import com.healthcare.ecosystem.allocation.model.enums.PatientGender;
import com.healthcare.ecosystem.allocation.model.enums.WardType;
import jakarta.persistence.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.Instant;

@Entity
@Table(name = "admissions")
public class Admission {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "patient_id", nullable = false, length = 100)
    private String patientId;

    @Enumerated(EnumType.STRING)
    @JdbcTypeCode(SqlTypes.VARCHAR)
    @Column(name = "patient_gender", nullable = false, length = 20)
    private PatientGender patientGender;

    @Enumerated(EnumType.STRING)
    @JdbcTypeCode(SqlTypes.VARCHAR)
    @Column(name = "required_ward_type", nullable = false, length = 50)
    private WardType requiredWardType;

    @Enumerated(EnumType.STRING)
    @JdbcTypeCode(SqlTypes.VARCHAR)
    @Column(name = "required_bed_type", nullable = false, length = 50)
    private BedType requiredBedType;

    @Enumerated(EnumType.STRING)
    @JdbcTypeCode(SqlTypes.VARCHAR)
    @Column(nullable = false, length = 30)
    private AdmissionPriority priority;

    @Column(name = "isolation_required", nullable = false)
    private boolean isolationRequired;

    @Enumerated(EnumType.STRING)
    @JdbcTypeCode(SqlTypes.VARCHAR)
    @Column(nullable = false, length = 30)
    private AdmissionStatus status;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "allocated_bed_id")
    private Bed allocatedBed;

    @Column(name = "reservation_expires_at")
    private Instant reservationExpiresAt;

    @Column(name = "admission_time")
    private Instant admissionTime;

    @Column(name = "discharge_time")
    private Instant dischargeTime;

    @Column(name = "cancellation_reason", length = 255)
    private String cancellationReason;

    @Version
    @Column(nullable = false)
    private Long version = 0L;

    @Column(name = "created_at", updatable = false)
    private Instant createdAt = Instant.now();

    @Column(name = "updated_at")
    private Instant updatedAt = Instant.now();

    public Admission() {}

    public Admission(String patientId, PatientGender patientGender, WardType requiredWardType,
                     BedType requiredBedType, AdmissionPriority priority, boolean isolationRequired) {
        this.patientId = patientId;
        this.patientGender = patientGender;
        this.requiredWardType = requiredWardType;
        this.requiredBedType = requiredBedType;
        this.priority = priority;
        this.isolationRequired = isolationRequired;
        this.status = AdmissionStatus.PENDING;
    }

    @PreUpdate
    public void preUpdate() {
        this.updatedAt = Instant.now();
    }

    public Long getId() { return id; }
    public String getPatientId() { return patientId; }
    public void setPatientId(String patientId) { this.patientId = patientId; }
    public PatientGender getPatientGender() { return patientGender; }
    public void setPatientGender(PatientGender patientGender) { this.patientGender = patientGender; }
    public WardType getRequiredWardType() { return requiredWardType; }
    public void setRequiredWardType(WardType requiredWardType) { this.requiredWardType = requiredWardType; }
    public BedType getRequiredBedType() { return requiredBedType; }
    public void setRequiredBedType(BedType requiredBedType) { this.requiredBedType = requiredBedType; }
    public AdmissionPriority getPriority() { return priority; }
    public void setPriority(AdmissionPriority priority) { this.priority = priority; }
    public boolean isIsolationRequired() { return isolationRequired; }
    public void setIsolationRequired(boolean isolationRequired) { this.isolationRequired = isolationRequired; }
    public AdmissionStatus getStatus() { return status; }
    public void setStatus(AdmissionStatus status) { this.status = status; }
    public Bed getAllocatedBed() { return allocatedBed; }
    public void setAllocatedBed(Bed allocatedBed) { this.allocatedBed = allocatedBed; }
    public Instant getReservationExpiresAt() { return reservationExpiresAt; }
    public void setReservationExpiresAt(Instant reservationExpiresAt) { this.reservationExpiresAt = reservationExpiresAt; }
    public Instant getAdmissionTime() { return admissionTime; }
    public void setAdmissionTime(Instant admissionTime) { this.admissionTime = admissionTime; }
    public Instant getDischargeTime() { return dischargeTime; }
    public void setDischargeTime(Instant dischargeTime) { this.dischargeTime = dischargeTime; }
    public String getCancellationReason() { return cancellationReason; }
    public void setCancellationReason(String cancellationReason) { this.cancellationReason = cancellationReason; }
    public Long getVersion() { return version; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }
}