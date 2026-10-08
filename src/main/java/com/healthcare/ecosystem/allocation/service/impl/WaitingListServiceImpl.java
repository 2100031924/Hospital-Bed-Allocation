package com.healthcare.ecosystem.allocation.service.impl;

import com.healthcare.ecosystem.allocation.model.entity.Admission;
import com.healthcare.ecosystem.allocation.model.entity.Bed;
import com.healthcare.ecosystem.allocation.model.entity.BedStatusLog;
import com.healthcare.ecosystem.allocation.model.entity.WaitingListEntry;
import com.healthcare.ecosystem.allocation.model.enums.AdmissionStatus;
import com.healthcare.ecosystem.allocation.model.enums.BedStatus;
import com.healthcare.ecosystem.allocation.repository.AdmissionRepository;
import com.healthcare.ecosystem.allocation.repository.BedRepository;
import com.healthcare.ecosystem.allocation.repository.BedStatusLogRepository;
import com.healthcare.ecosystem.allocation.repository.WaitingListRepository;
import com.healthcare.ecosystem.allocation.service.BedAllocationEngine;
import com.healthcare.ecosystem.allocation.service.WaitingListService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;

@Service
public class WaitingListServiceImpl implements WaitingListService {

    private static final Logger log = LoggerFactory.getLogger(WaitingListServiceImpl.class);

    private final WaitingListRepository waitingListRepository;
    private final AdmissionRepository admissionRepository;
    private final BedRepository bedRepository;
    private final BedAllocationEngine allocationEngine;
    private final BedStatusLogRepository bedStatusLogRepository;
    private final int reservationTimeoutMinutes;

    public WaitingListServiceImpl(WaitingListRepository waitingListRepository,
                                  AdmissionRepository admissionRepository,
                                  BedRepository bedRepository,
                                  BedAllocationEngine allocationEngine,
                                  BedStatusLogRepository bedStatusLogRepository,
                                  @Value("${allocation.reservation.timeout-minutes:15}")
                                  int reservationTimeoutMinutes) {
        this.waitingListRepository = waitingListRepository;
        this.admissionRepository = admissionRepository;
        this.bedRepository = bedRepository;
        this.allocationEngine = allocationEngine;
        this.bedStatusLogRepository = bedStatusLogRepository;
        this.reservationTimeoutMinutes = reservationTimeoutMinutes;
    }

    @Override
    @Transactional
    public void enqueue(Admission admission) {
        admission.setStatus(AdmissionStatus.WAITING_LIST);
        admissionRepository.save(admission);

        if (!waitingListRepository.existsByAdmissionId(admission.getId())) {
            WaitingListEntry entry = new WaitingListEntry(admission, admission.getPriority().getWeight());
            waitingListRepository.save(entry);
        }
        log.info("Admission {} enqueued on waiting list with score {}",
                admission.getId(), admission.getPriority().getWeight());
    }


    @Override
    @Transactional
    public void evaluateWaitingListForBed(Bed releasedBed) {
        if (releasedBed == null || releasedBed.getId() == null) {
            return;
        }

        Bed lockedBed = bedRepository.findByIdWithPessimisticLock(releasedBed.getId()).orElse(null);

        if (lockedBed == null || lockedBed.getStatus() != BedStatus.AVAILABLE) {
            return;
        }

        List<WaitingListEntry> waitingEntries =
                waitingListRepository.findAllOrderedByPriorityWithAdmission(AdmissionStatus.WAITING_LIST);

        for (WaitingListEntry entry : waitingEntries) {
            Admission candidateAdmission = entry.getAdmission();
            if (candidateAdmission == null) {
                continue;
            }
            if (candidateAdmission.getStatus() != AdmissionStatus.WAITING_LIST) {
                // Stale entry (e.g. admission cancelled or already allocated elsewhere): clean it up.
                waitingListRepository.delete(entry);
                continue;
            }

            if (!allocationEngine.isBedEligibleForAdmission(lockedBed, candidateAdmission)) {
                continue;
            }

            lockedBed.setStatus(BedStatus.RESERVED);
            bedRepository.save(lockedBed);

            candidateAdmission.setStatus(AdmissionStatus.RESERVED);
            candidateAdmission.setAllocatedBed(lockedBed);
            candidateAdmission.setReservationExpiresAt(
                    Instant.now().plus(reservationTimeoutMinutes, ChronoUnit.MINUTES));
            admissionRepository.save(candidateAdmission);

            waitingListRepository.delete(entry);

            bedStatusLogRepository.save(new BedStatusLog(
                    lockedBed,
                    BedStatus.AVAILABLE,
                    BedStatus.RESERVED,
                    "WAITING_LIST_SCHEDULER",
                    "Allocated from waiting list to admission ID: " + candidateAdmission.getId()
            ));
            log.info("Bed {} auto-reserved for waiting admission {}",
                    lockedBed.getId(), candidateAdmission.getId());
            break;
        }
    }
}