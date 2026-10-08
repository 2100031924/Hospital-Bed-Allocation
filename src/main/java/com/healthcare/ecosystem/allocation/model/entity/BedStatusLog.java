package com.healthcare.ecosystem.allocation.model.entity;

import com.healthcare.ecosystem.allocation.model.enums.BedStatus;
import jakarta.persistence.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.Instant;

@Entity
@Table(name = "bed_status_logs")
public class BedStatusLog {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "bed_id", nullable = false)
    private Bed bed;

    @Enumerated(EnumType.STRING)
    @JdbcTypeCode(SqlTypes.VARCHAR)
    @Column(name = "previous_status", nullable = false, length = 30)
    private BedStatus previousStatus;

    @Enumerated(EnumType.STRING)
    @JdbcTypeCode(SqlTypes.VARCHAR)
    @Column(name = "new_status", nullable = false, length = 30)
    private BedStatus newStatus;

    @Column(name = "changed_by", nullable = false, length = 100)
    private String changedBy = "SYSTEM";

    @Column(length = 255)
    private String reason;

    @Column(name = "logged_at", updatable = false)
    private Instant loggedAt = Instant.now();

    public BedStatusLog() {}

    public BedStatusLog(Bed bed, BedStatus previousStatus, BedStatus newStatus, String changedBy, String reason) {
        this.bed = bed;
        this.previousStatus = previousStatus;
        this.newStatus = newStatus;
        this.changedBy = changedBy == null ? "SYSTEM" : changedBy;
        this.reason = reason;
    }

    public Long getId() { return id; }
    public Bed getBed() { return bed; }
    public void setBed(Bed bed) { this.bed = bed; }
    public BedStatus getPreviousStatus() { return previousStatus; }
    public BedStatus getNewStatus() { return newStatus; }
    public String getChangedBy() { return changedBy; }
    public String getReason() { return reason; }
    public Instant getLoggedAt() { return loggedAt; }
}