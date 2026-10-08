package com.healthcare.ecosystem.allocation.service.impl;

import com.healthcare.ecosystem.allocation.dto.request.AdmissionCreationRequest;
import com.healthcare.ecosystem.allocation.dto.response.AdmissionResponse;
import com.healthcare.ecosystem.allocation.exception.BedAllocationConflictException;
import com.healthcare.ecosystem.allocation.exception.BedUnavailableException;
import com.healthcare.ecosystem.allocation.exception.InvalidStateTransitionException;
import com.healthcare.ecosystem.allocation.exception.ResourceNotFoundException;
import com.healthcare.ecosystem.allocation.model.entity.Admission;
import com.healthcare.ecosystem.allocation.model.entity.Bed;
import com.healthcare.ecosystem.allocation.model.entity.BedStatusLog;
import com.healthcare.ecosystem.allocation.model.enums.AdmissionStatus;
import com.healthcare.ecosystem.allocation.model.enums.BedStatus;
import com.healthcare.ecosystem.allocation.repository.AdmissionRepository;
import com.healthcare.ecosystem.allocation.repository.BedRepository;
import com.healthcare.ecosystem.allocation.repository.BedStatusLogRepository;
import com.healthcare.ecosystem.allocation.repository.WaitingListRepository;
import com.healthcare.ecosystem.allocation.service.AdmissionService;
import com.healthcare.ecosystem.allocation.service.BedAllocationEngine;
import com.healthcare.ecosystem.allocation.service.WaitingListService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.ConcurrencyFailureException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Optional;

import jakarta.persistence.LockTimeoutException;
import jakarta.persistence.PessimisticLockException;

@Service
public class AdmissionServiceImpl implements AdmissionService {

    private static final Logger log = LoggerFactory.getLogger(AdmissionServiceImpl.class);

    private final AdmissionRepository admissionRepository;
    private final BedRepository bedRepository;
    private final BedAllocationEngine bedAllocationEngine;
    private final WaitingListService waitingListService;
    private final BedStatusLogRepository bedStatusLogRepository;
    private final WaitingListRepository waitingListRepository;
    private final int reservationTimeoutMinutes;

    public AdmissionServiceImpl(AdmissionRepository admissionRepository,
                                BedRepository bedRepository,
                                BedAllocationEngine bedAllocationEngine,
                                WaitingListService waitingListService,
                                BedStatusLogRepository bedStatusLogRepository,
                                WaitingListRepository waitingListRepository,
                                @Value("${allocation.reservation.timeout-minutes:15}")
                                int reservationTimeoutMinutes) {
        this.admissionRepository = admissionRepository;
        this.bedRepository = bedRepository;
        this.bedAllocationEngine = bedAllocationEngine;
        this.waitingListService = waitingListService;
        this.bedStatusLogRepository = bedStatusLogRepository;
        this.waitingListRepository = waitingListRepository;
        this.reservationTimeoutMinutes = reservationTimeoutMinutes;
    }


    @Override
    @Transactional(isolation = Isolation.READ_COMMITTED)
    public AdmissionResponse createAdmissionRequest(AdmissionCreationRequest request) {
        Admission admission = new Admission(
                request.patientId(),
                request.patientGender(),
                request.requiredWardType(),
                request.requiredBedType(),
                request.priority(),
                request.isolationRequired()
        );
        admission = admissionRepository.save(admission);

        Optional<Bed> allocatedBedOpt = bedAllocationEngine.allocateBed(admission);

        if (allocatedBedOpt.isPresent()) {
            Bed bed = allocatedBedOpt.get();
            BedStatus previousStatus = bed.getStatus();
            bed.setStatus(BedStatus.RESERVED);
            bedRepository.save(bed);

            admission.setAllocatedBed(bed);
            admission.setStatus(AdmissionStatus.RESERVED);
            admission.setReservationExpiresAt(
                    Instant.now().plus(reservationTimeoutMinutes, ChronoUnit.MINUTES));
            admission = admissionRepository.save(admission);

            bedStatusLogRepository.save(new BedStatusLog(
                    bed, previousStatus, BedStatus.RESERVED, "ALLOCATION_ENGINE",
                    "Admission: " + admission.getId()
            ));
            log.info("Admission {} auto-allocated bed {}", admission.getId(), bed.getId());
        } else {
            waitingListService.enqueue(admission);
        }

        return mapToResponse(admission);
    }

    @Override
    @Transactional(isolation = Isolation.READ_COMMITTED)
    public AdmissionResponse reserveBedManually(Long admissionId, Long bedId) {
        Admission admission = lockAdmission(admissionId);

        if (admission.getStatus() != AdmissionStatus.WAITING_LIST
                && admission.getStatus() != AdmissionStatus.PENDING) {
            throw new InvalidStateTransitionException(
                    "Admission cannot be reserved from status: " + admission.getStatus());
        }

        Bed bed;
        try {
            bed = bedRepository.findByIdWithPessimisticLock(bedId)
                    .orElseThrow(() -> new ResourceNotFoundException("Bed not found: " + bedId));
        } catch (ConcurrencyFailureException | PessimisticLockException | LockTimeoutException ex) {
            throw BedAllocationConflictException.from(ex);
        }


        if (bedAllocationEngine.isBedAlreadyHeld(bed)) {
            throw new BedAllocationConflictException(
                    "Bed " + bedId + " is being reserved by another concurrent request. Please retry.");
        }

        if (!bedAllocationEngine.isBedEligibleForAdmission(bed, admission)) {
            throw new BedUnavailableException(
                    "Bed ID " + bedId + " is not eligible for this admission request.");
        }

        BedStatus previousStatus = bed.getStatus();
        bed.setStatus(BedStatus.RESERVED);
        bedRepository.save(bed);

        admission.setAllocatedBed(bed);
        admission.setStatus(AdmissionStatus.RESERVED);
        admission.setReservationExpiresAt(
                Instant.now().plus(reservationTimeoutMinutes, ChronoUnit.MINUTES));
        admission = admissionRepository.save(admission);

        waitingListRepository.deleteByAdmissionId(admissionId);

        bedStatusLogRepository.save(new BedStatusLog(
                bed, previousStatus, BedStatus.RESERVED, "MANUAL_RESERVATION",
                "Admission: " + admissionId
        ));

        return mapToResponse(admission);
    }

    @Override
    @Transactional
    public AdmissionResponse confirmAdmission(Long admissionId) {
        Admission admission = lockAdmission(admissionId);

        if (admission.getStatus() != AdmissionStatus.RESERVED) {
            throw new InvalidStateTransitionException(
                    "Admission must be in RESERVED state to confirm. Current: " + admission.getStatus());
        }
        if (admission.getReservationExpiresAt() != null
                && admission.getReservationExpiresAt().isBefore(Instant.now())) {
            throw new InvalidStateTransitionException(
                    "Reservation hold has already expired and is awaiting cleanup. Current: "
                            + admission.getStatus());
        }

        Bed bed = lockAllocatedBed(admission);
        BedStatus previousStatus = bed.getStatus();
        bed.setStatus(BedStatus.OCCUPIED);
        bedRepository.save(bed);

        admission.setStatus(AdmissionStatus.ADMITTED);
        admission.setAdmissionTime(Instant.now());
        admission.setReservationExpiresAt(null);
        admission = admissionRepository.save(admission);

        bedStatusLogRepository.save(new BedStatusLog(
                bed, previousStatus, BedStatus.OCCUPIED, "ADMISSION_CONFIRM",
                "Patient admitted: " + admission.getPatientId()
        ));

        return mapToResponse(admission);
    }


    @Override
    @Transactional
    public AdmissionResponse dischargeAdmission(Long admissionId) {
        Admission admission = lockAdmission(admissionId);

        if (admission.getStatus() != AdmissionStatus.ADMITTED) {
            throw new InvalidStateTransitionException(
                    "Cannot discharge admission that is not ADMITTED. Current: " + admission.getStatus());
        }

        Bed bed = lockAllocatedBed(admission);
        BedStatus previousStatus = bed.getStatus();
        bed.setStatus(BedStatus.MAINTENANCE);
        bedRepository.save(bed);

        admission.setStatus(AdmissionStatus.DISCHARGED);
        admission.setDischargeTime(Instant.now());
        admission = admissionRepository.save(admission);

        bedStatusLogRepository.save(new BedStatusLog(
                bed, previousStatus, BedStatus.MAINTENANCE, "DISCHARGE_WORKFLOW",
                "Admission: " + admissionId
        ));

        return mapToResponse(admission);
    }


    @Override
    @Transactional
    public AdmissionResponse cancelAdmission(Long admissionId, String reason) {
        Admission admission = lockAdmission(admissionId);

        if (admission.getStatus() == AdmissionStatus.DISCHARGED
                || admission.getStatus() == AdmissionStatus.CANCELLED) {
            throw new InvalidStateTransitionException(
                    "Admission is already closed. Current: " + admission.getStatus());
        }
        if (admission.getStatus() == AdmissionStatus.PENDING
                || admission.getStatus() == AdmissionStatus.WAITING_LIST
                || admission.getStatus() == AdmissionStatus.EXPIRED) {
            waitingListRepository.deleteByAdmissionId(admissionId);
        }

        Bed bed = admission.getAllocatedBed() != null ? lockAllocatedBed(admission) : null;


        admission.setStatus(AdmissionStatus.CANCELLED);
        admission.setCancellationReason(reason);
        admission.setReservationExpiresAt(null);
        admission.setAllocatedBed(null);
        admission = admissionRepository.save(admission);

        if (bed != null && (bed.getStatus() == BedStatus.RESERVED || bed.getStatus() == BedStatus.OCCUPIED)) {
            BedStatus previousStatus = bed.getStatus();
            bed.setStatus(BedStatus.AVAILABLE);
            bedRepository.save(bed);

            bedStatusLogRepository.save(new BedStatusLog(
                    bed, previousStatus, BedStatus.AVAILABLE, "ADMISSION_CANCEL",
                    "Admission: " + admissionId + " reason: " + reason
            ));

            waitingListService.evaluateWaitingListForBed(bed);
        }

        return mapToResponse(admission);
    }

    @Override
    @Transactional(readOnly = true)
    public AdmissionResponse getAdmissionById(Long admissionId) {
        Admission admission = admissionRepository.findById(admissionId)
                .orElseThrow(() -> new ResourceNotFoundException("Admission not found: " + admissionId));
        return mapToResponse(admission);
    }

    @Override
    @Transactional(readOnly = true)
    public List<AdmissionResponse> getActiveAdmissionsForPatient(String patientId) {
        return admissionRepository
                .findByPatientIdAndStatusIn(patientId,
                        List.of(AdmissionStatus.PENDING, AdmissionStatus.WAITING_LIST,
                                AdmissionStatus.RESERVED, AdmissionStatus.ADMITTED))
                .stream()
                .map(this::mapToResponse)
                .toList();
    }

    private Admission lockAdmission(Long admissionId) {
        try {
            return admissionRepository.findByIdWithPessimisticLock(admissionId)
                    .orElseThrow(() -> new ResourceNotFoundException("Admission not found: " + admissionId));
        } catch (ConcurrencyFailureException | PessimisticLockException | LockTimeoutException ex) {
            throw BedAllocationConflictException.from(ex);
        }
    }

    private Bed lockAllocatedBed(Admission admission) {
        if (admission.getAllocatedBed() == null) {
            throw new InvalidStateTransitionException(
                    "Admission " + admission.getId() + " has no allocated bed.");
        }
        try {
            return bedRepository.findByIdWithPessimisticLock(admission.getAllocatedBed().getId())
                    .orElseThrow(() -> new ResourceNotFoundException("Allocated bed not found."));
        } catch (ConcurrencyFailureException | PessimisticLockException | LockTimeoutException ex) {
            throw BedAllocationConflictException.from(ex);
        }
    }

    private AdmissionResponse mapToResponse(Admission admission) {
        return new AdmissionResponse(
                admission.getId(),
                admission.getPatientId(),
                admission.getPatientGender(),
                admission.getRequiredWardType(),
                admission.getRequiredBedType(),
                admission.getPriority(),
                admission.isIsolationRequired(),
                admission.getStatus(),
                admission.getAllocatedBed() != null ? admission.getAllocatedBed().getId() : null,
                admission.getReservationExpiresAt(),
                admission.getAdmissionTime(),
                admission.getDischargeTime(),
                admission.getCancellationReason()
        );
    }
}