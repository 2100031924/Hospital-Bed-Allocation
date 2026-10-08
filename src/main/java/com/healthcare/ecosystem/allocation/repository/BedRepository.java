package com.healthcare.ecosystem.allocation.repository;

import com.healthcare.ecosystem.allocation.model.entity.Bed;
import com.healthcare.ecosystem.allocation.model.enums.BedStatus;
import com.healthcare.ecosystem.allocation.model.enums.BedType;
import com.healthcare.ecosystem.allocation.model.enums.GenderPolicy;
import com.healthcare.ecosystem.allocation.model.enums.WardType;
import jakarta.persistence.LockModeType;
import jakarta.persistence.QueryHint;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.jpa.repository.QueryHints;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface BedRepository extends JpaRepository<Bed, Long> {

    List<Bed> findByRoomWardId(Long wardId);


    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @QueryHints({
            @QueryHint(name = "jakarta.persistence.lock.timeout", value = "0"),
            // NOTE: value must be the bare enum constant name ("BYPASS"). Hibernate resolves
            // it via CacheRetrieveMode.valueOf(...), so a fully-qualified path throws
            // IllegalArgumentException at query execution time.
            @QueryHint(name = "jakarta.persistence.cache.retrieveMode", value = "BYPASS")
    })
    @Query("SELECT b FROM Bed b WHERE b.id = :id")
    Optional<Bed> findByIdWithPessimisticLock(@Param("id") Long id);


    // NOTE on the gender filter: it is bound as a precomputed policy collection, NOT as
    // a nullable enum parameter. Hibernate 6.4 cannot infer the type of a parameter that
    // appears in ":p IS NULL" but is never compared to a mapped attribute (here it was only
    // compared to enum literals), and query-plan building aborts with
    // "Could not determine ValueMapping for SqmParameter(gender)". IN infers the element
    // type from the path - the same pattern as the other working filters.
    @Query("SELECT b FROM Bed b " +
           "JOIN FETCH b.room r " +
           "JOIN FETCH r.ward w " +
           "WHERE (:hospitalId IS NULL OR w.hospital.id = :hospitalId) " +
           "AND (:wardId IS NULL OR w.id = :wardId) " +
           "AND (:bedType IS NULL OR b.bedType = :bedType) " +
           "AND (:isolationRequired IS NULL OR r.isolationRoom = :isolationRequired) " +
           "AND (:wardType IS NULL OR w.wardType = :wardType) " +
           "AND w.genderPolicy IN :allowedPolicies " +
           "AND b.status = com.healthcare.ecosystem.allocation.model.enums.BedStatus.AVAILABLE " +
           "ORDER BY b.id ASC")
    List<Bed> searchAvailableBeds(
            @Param("hospitalId") Long hospitalId,
            @Param("wardId") Long wardId,
            @Param("bedType") BedType bedType,
            @Param("isolationRequired") Boolean isolationRequired,
            @Param("wardType") WardType wardType,
            @Param("allowedPolicies") List<GenderPolicy> allowedPolicies
    );


    @Query("SELECT b.id FROM Bed b " +
           "JOIN b.room r " +
           "JOIN r.ward w " +
           "WHERE w.wardType = :wardType " +
           "AND b.bedType = :bedType " +
           "AND r.isolationRoom = :isolationRequired " +
           "AND b.status = com.healthcare.ecosystem.allocation.model.enums.BedStatus.AVAILABLE " +
           "ORDER BY b.id ASC")
    List<Long> findEligibleCandidateBedIds(
            @Param("wardType") WardType wardType,
            @Param("bedType") BedType bedType,
            @Param("isolationRequired") boolean isolationRequired
    );

    @Query("SELECT b FROM Bed b WHERE b.room.id = :roomId AND b.status IN ('OCCUPIED', 'RESERVED')")
    List<Bed> findActiveBedsInRoom(@Param("roomId") Long roomId);

    @Query("SELECT COUNT(b) FROM Bed b WHERE b.status = :status")
    long countByStatus(@Param("status") BedStatus status);
}