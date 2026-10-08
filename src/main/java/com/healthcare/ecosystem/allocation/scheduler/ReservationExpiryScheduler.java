package com.healthcare.ecosystem.allocation.scheduler;

import com.healthcare.ecosystem.allocation.model.entity.Admission;
import com.healthcare.ecosystem.allocation.model.entity.Bed;
import com.healthcare.ecosystem.allocation.model.entity.BedStatusLog;
import com.healthcare.ecosystem.allocation.model.enums.AdmissionStatus;
import com.healthcare.ecosystem.allocation.model.enums.BedStatus;
import com.healthcare.ecosystem.allocation.repository.AdmissionRepository;
import com.healthcare.ecosystem.allocation.repository.BedRepository;
import com.healthcare.ecosystem.allocation.repository.BedStatusLogRepository;
import com.healthcare.ecosystem.allocation.service.WaitingListService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;


@Component
public class ReservationExpiryScheduler {

    private static final Logger log = LoggerFactory.getLogger(ReservationExpiryScheduler.class);

    private final AdmissionRepository admissionRepository;
    private final BedRepository bedRepository;
    private final WaitingListService waitingListService;
    private final BedStatusLogRepository bedStatusLogRepository;

    public ReservationExpiryScheduler(AdmissionRepository admissionRepository,
                                      BedRepository bedRepository,
                                      WaitingListService waitingListService,
                                      BedStatusLogRepository bedStatusLogRepository) {
        this.admissionRepository = admissionRepository;
        this.bedRepository = bedRepository;
        this.waitingListService = waitingListService;
        this.bedStatusLogRepository = bedStatusLogRepository;
    }

    @Scheduled(cron = "${allocation.reservation.cleanup-cron:0 */1 * * * *}")
    @Transactional
    public void processExpiredReservations() {
        Instant now = Instant.now();
        List<Admission> expiredAdmissions = admissionRepository.findExpiredReservations(now);

        if (expiredAdmissions.isEmpty()) {
            return;
        }
        log.info("Found {} expired reservation(s) to reclaim.", expiredAdmissions.size());

        for (Admission admission : expiredAdmissions) {
            Bed bed = admission.getAllocatedBed();
            if (bed == null) {
                // No bed attached: just close the admission out.
                admission.setStatus(AdmissionStatus.EXPIRED);
                admission.setCancellationReason("Reservation hold period elapsed");
                admissionRepository.save(admission);
                continue;
            }

            Bed lockedBed = bedRepository.findByIdWithPessimisticLock(bed.getId()).orElse(null);
            if (lockedBed == null) {
                log.warn("Bed {} referenced by expired admission {} no longer exists", bed.getId(), admission.getId());
                continue;
            }

            if (lockedBed.getStatus() == BedStatus.RESERVED) {
                BedStatus previousStatus = lockedBed.getStatus();
                lockedBed.setStatus(BedStatus.AVAILABLE);
                bedRepository.save(lockedBed);

                bedStatusLogRepository.save(new BedStatusLog(
                        lockedBed, previousStatus, BedStatus.AVAILABLE, "EXPIRY_SCHEDULER",
                        "Reservation expired for admission: " + admission.getId()
                ));

                admission.setStatus(AdmissionStatus.EXPIRED);
                admission.setCancellationReason("Reservation hold period elapsed");
                admission.setAllocatedBed(null);
                admissionRepository.save(admission);

                log.info("Reclaimed bed {} from expired admission {}", lockedBed.getId(), admission.getId());

                // Offer the reclaimed bed to the waiting list (highest priority first).
                waitingListService.evaluateWaitingListForBed(lockedBed);
            }

        }
    }
}