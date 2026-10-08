package com.healthcare.ecosystem.allocation.repository;

import com.healthcare.ecosystem.allocation.model.entity.Admission;
import com.healthcare.ecosystem.allocation.model.enums.AdmissionStatus;
import jakarta.persistence.LockModeType;
import jakarta.persistence.QueryHint;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.jpa.repository.QueryHints;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

public interface AdmissionRepository extends JpaRepository<Admission, Long> {

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @QueryHints(@QueryHint(name = "jakarta.persistence.lock.timeout", value = "0"))
    @Query("SELECT a FROM Admission a WHERE a.id = :id")
    Optional<Admission> findByIdWithPessimisticLock(@Param("id") Long id);

    Optional<Admission> findByAllocatedBedIdAndStatusIn(Long bedId, List<AdmissionStatus> statuses);

    @Query("SELECT a FROM Admission a WHERE a.status = 'RESERVED' AND a.reservationExpiresAt < :now")
    List<Admission> findExpiredReservations(@Param("now") Instant now);

    List<Admission> findByPatientIdAndStatusIn(String patientId, List<AdmissionStatus> statuses);

    List<Admission> findByStatusOrderByIdAsc(AdmissionStatus status);
}