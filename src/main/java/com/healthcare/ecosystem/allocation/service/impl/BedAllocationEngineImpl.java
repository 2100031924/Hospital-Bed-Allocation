package com.healthcare.ecosystem.allocation.service.impl;

import com.healthcare.ecosystem.allocation.model.entity.Admission;
import com.healthcare.ecosystem.allocation.model.entity.Bed;
import com.healthcare.ecosystem.allocation.model.entity.Room;
import com.healthcare.ecosystem.allocation.model.entity.Ward;
import com.healthcare.ecosystem.allocation.model.enums.AdmissionStatus;
import com.healthcare.ecosystem.allocation.model.enums.BedStatusTransition;
import com.healthcare.ecosystem.allocation.model.enums.GenderPolicy;
import com.healthcare.ecosystem.allocation.model.enums.PatientGender;
import com.healthcare.ecosystem.allocation.repository.AdmissionRepository;
import com.healthcare.ecosystem.allocation.repository.BedRepository;
import com.healthcare.ecosystem.allocation.service.BedAllocationEngine;
import jakarta.persistence.LockTimeoutException;
import jakarta.persistence.PessimisticLockException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.ConcurrencyFailureException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Optional;

@Service
public class BedAllocationEngineImpl implements BedAllocationEngine {

    private static final Logger log = LoggerFactory.getLogger(BedAllocationEngineImpl.class);

    /**
     * Admission states that constitute an active claim on a bed. A bed referenced by any of
     * these must not be allocated to anybody else.
     */
    private static final List<AdmissionStatus> ACTIVE_ADMISSION_STATUSES =
            List.of(AdmissionStatus.RESERVED, AdmissionStatus.ADMITTED);

    private final BedRepository bedRepository;
    private final AdmissionRepository admissionRepository;

    public BedAllocationEngineImpl(BedRepository bedRepository, AdmissionRepository admissionRepository) {
        this.bedRepository = bedRepository;
        this.admissionRepository = admissionRepository;
    }


    @Override
    @Transactional
    public Optional<Bed> allocateBed(Admission admission) {
        if (admission == null) {
            return Optional.empty();
        }
        List<Long> candidateBedIds = bedRepository.findEligibleCandidateBedIds(
                admission.getRequiredWardType(),
                admission.getRequiredBedType(),
                admission.isIsolationRequired()
        );

        for (Long candidateId : candidateBedIds) {
            Optional<Bed> lockedBedOpt;
            try {
                lockedBedOpt = bedRepository.findByIdWithPessimisticLock(candidateId);
            } catch (ConcurrencyFailureException | PessimisticLockException | LockTimeoutException ex) {
                // Locked by a concurrent allocator: treat as a lost race and try the next candidate.
                log.debug("Bed {} is locked by a concurrent transaction, skipping candidate", candidateId);
                continue;
            }

            if (lockedBedOpt.isEmpty()) {
                continue;
            }

            Bed lockedBed = lockedBedOpt.get();
            if (isBedEligibleForAdmission(lockedBed, admission)) {
                return Optional.of(lockedBed);
            }
        }
        return Optional.empty();
    }

    @Override
    public boolean isBedEligibleForAdmission(Bed bed, Admission admission) {
        if (bed == null || admission == null) {
            return false;
        }

        // 1. Status / availability check
        //    BLOCKED and MAINTENANCE beds are structurally not allocatable.
        if (!BedStatusTransition.isAllowedToReceivePatient(bed.getStatus())) {
            return false;
        }

        Room room = bed.getRoom();
        if (room == null) {
            return false;
        }
        Ward ward = room.getWard();
        if (ward == null) {
            return false;
        }

        // 2. Existing reservation / occupancy check.
        //    Defence in depth behind the status check: a bed that already has a live
        //    admission attached must never be handed to a second patient, even if the
        //    status column somehow still reads AVAILABLE.
        //    Delegates to isBedAlreadyHeld so unpersisted beds (null id, unit tests)
        //    short-circuit to false without issuing a query with a null bind parameter.
        if (isBedAlreadyHeld(bed)) {
            return false;
        }

        // 3. Required ward check
        if (ward.getWardType() != admission.getRequiredWardType()) {
            return false;
        }

        // 4. Required bed type check
        if (bed.getBedType() != admission.getRequiredBedType()) {
            return false;
        }

        // 5. Ward Gender Policy Check
        if (ward.getGenderPolicy() == GenderPolicy.MALE_ONLY
                && admission.getPatientGender() != PatientGender.MALE) {
            return false;
        }
        if (ward.getGenderPolicy() == GenderPolicy.FEMALE_ONLY
                && admission.getPatientGender() != PatientGender.FEMALE) {
            return false;
        }

        // 6. Isolation Compatibility Check
        if (admission.isIsolationRequired()) {
            if (!room.isIsolationRoom()) {
                return false;
            }
            // An isolation room must have zero active (occupied/reserved) beds
            if (!bedRepository.findActiveBedsInRoom(room.getId()).isEmpty()) {
                return false;
            }
        } else {
            // A non-isolation patient must not consume a room flagged as an isolation room
            if (room.isIsolationRoom()) {
                return false;
            }
        }

        // 7. Room Gender Compatibility for Multi-bed rooms
        for (Bed activeBed : bedRepository.findActiveBedsInRoom(room.getId())) {
            Optional<Admission> activeAdmissionOpt = admissionRepository.findByAllocatedBedIdAndStatusIn(
                    activeBed.getId(),
                    ACTIVE_ADMISSION_STATUSES
            );
            if (activeAdmissionOpt.isPresent()) {
                Admission activeAdmission = activeAdmissionOpt.get();
                if (activeAdmission.getPatientGender() != admission.getPatientGender()) {
                    return false; // Prevent co-ed placement in multi-bed non-isolation rooms
                }
            }
        }

        return true;
    }

    @Override
    public boolean isBedAlreadyHeld(Bed bed) {
        if (bed == null || bed.getId() == null) {
            return false;
        }
        return admissionRepository
                .findByAllocatedBedIdAndStatusIn(bed.getId(), ACTIVE_ADMISSION_STATUSES)
                .isPresent();
    }
}