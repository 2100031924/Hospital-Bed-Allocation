package com.healthcare.ecosystem.allocation.model.entity;

import jakarta.persistence.*;
import java.time.Instant;

@Entity
@Table(name = "waiting_list")
public class WaitingListEntry {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @OneToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "admission_id", nullable = false, unique = true)
    private Admission admission;

    @Column(name = "priority_score", nullable = false)
    private int priorityScore;

    @Column(name = "enqueued_at", updatable = false)
    private Instant enqueuedAt = Instant.now();

    public WaitingListEntry() {}

    public WaitingListEntry(Admission admission, int priorityScore) {
        this.admission = admission;
        this.priorityScore = priorityScore;
    }

    public Long getId() { return id; }
    public Admission getAdmission() { return admission; }
    public void setAdmission(Admission admission) { this.admission = admission; }
    public int getPriorityScore() { return priorityScore; }
    public void setPriorityScore(int priorityScore) { this.priorityScore = priorityScore; }
    public Instant getEnqueuedAt() { return enqueuedAt; }
}