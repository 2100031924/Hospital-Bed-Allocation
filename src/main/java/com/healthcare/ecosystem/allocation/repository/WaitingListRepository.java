package com.healthcare.ecosystem.allocation.repository;

import com.healthcare.ecosystem.allocation.model.entity.WaitingListEntry;
import com.healthcare.ecosystem.allocation.model.enums.AdmissionStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;


public interface WaitingListRepository extends JpaRepository<WaitingListEntry, Long> {

    @Query("SELECT w FROM WaitingListEntry w ORDER BY w.priorityScore DESC, w.enqueuedAt ASC")
    List<WaitingListEntry> findAllOrderedByPriority();


    @Query("SELECT w FROM WaitingListEntry w " +
           "JOIN FETCH w.admission a " +
           "WHERE a.status = :status " +
           "ORDER BY w.priorityScore DESC, w.enqueuedAt ASC")
    List<WaitingListEntry> findAllOrderedByPriorityWithAdmission(
            @Param("status") AdmissionStatus status);

    Optional<WaitingListEntry> findByAdmissionId(Long admissionId);

    boolean existsByAdmissionId(Long admissionId);

    void deleteByAdmissionId(Long admissionId);
}