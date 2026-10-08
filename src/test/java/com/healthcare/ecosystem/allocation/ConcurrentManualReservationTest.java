package com.healthcare.ecosystem.allocation;

import com.healthcare.ecosystem.allocation.dto.request.AdmissionCreationRequest;
import com.healthcare.ecosystem.allocation.dto.response.AdmissionResponse;
import com.healthcare.ecosystem.allocation.model.entity.Bed;
import com.healthcare.ecosystem.allocation.model.entity.Hospital;
import com.healthcare.ecosystem.allocation.model.entity.Room;
import com.healthcare.ecosystem.allocation.model.entity.Ward;
import com.healthcare.ecosystem.allocation.model.enums.*;
import com.healthcare.ecosystem.allocation.repository.*;
import com.healthcare.ecosystem.allocation.service.AdmissionService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import java.util.List;
import java.util.concurrent.*;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Races two operators manually reserving the SAME bed for two different waiting admissions.
 *
 * Distinct from {@link BedAllocationConcurrencyTest}, which races the automatic allocation
 * engine. Here both threads are told exactly which bed to take, so the only thing standing
 * between them and a double-booking is the row lock plus the re-validation of bed state.
 */
@SpringBootTest
@ActiveProfiles("test")
class ConcurrentManualReservationTest {

    @Autowired
    private AdmissionService admissionService;

    @Autowired
    private AdmissionRepository admissionRepository;
    @Autowired
    private WaitingListRepository waitingListRepository;
    @Autowired
    private BedStatusLogRepository bedStatusLogRepository;
    @Autowired
    private BedRepository bedRepository;
    @Autowired
    private RoomRepository roomRepository;
    @Autowired
    private WardRepository wardRepository;
    @Autowired
    private HospitalRepository hospitalRepository;

    private Bed bed;

    @BeforeEach
    void setUp() {
        // Children before parents (see AdmissionLifecycleIntegrationTest.setUp).
        waitingListRepository.deleteAll();
        bedStatusLogRepository.deleteAll();
        admissionRepository.deleteAll();
        bedRepository.deleteAll();
        roomRepository.deleteAll();
        wardRepository.deleteAll();
        hospitalRepository.deleteAll();

        Hospital hospital = hospitalRepository.save(new Hospital("H-MR", "Manual Reserve Hospital", "Street 3"));
        Ward ward = wardRepository.save(
                new Ward(hospital, "MR Ward", WardType.GENERAL, GenderPolicy.UNISEX));
        Room room = roomRepository.save(new Room(ward, "MR-1", false));
        // Only one bed exists; it is parked in MAINTENANCE below so both admissions
        // reach WAITING_LIST without auto-allocating.
        bed = bedRepository.save(new Bed(room, "MR-BED-1", BedType.STANDARD));
    }

    @Test
    @DisplayName("Concurrent reservation requests for the same bed: exactly one wins, "
            + "the loser gets a conflict (never a double booking)")
    void twoOperatorsCannotReserveSameBed() throws InterruptedException {
        // Park the only bed in MAINTENANCE so both admissions fall through to the waiting list.
        // Reassign the merged copy: saveAndFlush returns a new managed instance with the
        // bumped @Version; the old detached reference would go stale on the next merge.
        bed.setStatus(BedStatus.MAINTENANCE);
        bed = bedRepository.saveAndFlush(bed);

        AdmissionResponse a = admissionService.createAdmissionRequest(
                new AdmissionCreationRequest("PAT-MR-A", PatientGender.MALE, WardType.GENERAL,
                        BedType.STANDARD, AdmissionPriority.NORMAL, false));
        AdmissionResponse b = admissionService.createAdmissionRequest(
                new AdmissionCreationRequest("PAT-MR-B", PatientGender.MALE, WardType.GENERAL,
                        BedType.STANDARD, AdmissionPriority.URGENT, false));

        assertEquals(AdmissionStatus.WAITING_LIST, a.status());
        assertEquals(AdmissionStatus.WAITING_LIST, b.status());

        // Housekeeping releases the bed; both operators now race to claim it.
        // Reassign again for the same detached-version reason as above.
        bed.setStatus(BedStatus.AVAILABLE);
        bed = bedRepository.saveAndFlush(bed);

        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(2);
        CountDownLatch success = new CountDownLatch(1);

        List<String> failures = java.util.Collections.synchronizedList(new java.util.ArrayList<>());

        for (AdmissionResponse target : List.of(a, b)) {
            pool.submit(() -> {
                try {
                    start.await();
                    admissionService.reserveBedManually(target.admissionId(), bed.getId());
                    success.countDown();
                } catch (Exception ex) {
                    failures.add(ex.getClass().getSimpleName() + ": " + ex.getMessage());
                } finally {
                    done.countDown();
                }
            });
        }

        start.countDown();
        assertTrue(done.await(60, TimeUnit.SECONDS), "Both threads must finish");
        pool.shutdown();
        assertTrue(pool.awaitTermination(30, TimeUnit.SECONDS));

        // Exactly one reservation may succeed
        assertEquals(0, success.getCount(),
                "Exactly one thread must win the bed; failures were: " + failures);

        // The loser must be a retryable conflict, not a silent double booking
        assertEquals(1, failures.size(), "Exactly one thread must fail");

        long reservedAdmissions =
                admissionRepository.findByStatusOrderByIdAsc(AdmissionStatus.RESERVED).size();
        assertEquals(1, reservedAdmissions,
                "Only one admission may hold the bed (got " + reservedAdmissions + ")");

        assertEquals(BedStatus.RESERVED,
                bedRepository.findById(bed.getId()).orElseThrow().getStatus(),
                "Bed must end RESERVED exactly once");

        // The winner must have been dequeued from the waiting list
        assertEquals(1, waitingListRepository.count(),
                "Exactly one waiting entry should remain (the loser)");
    }

    @Test
    @DisplayName("Concurrent confirm on the same admission: exactly one succeeds")
    void doubleConfirmIsRejected() throws InterruptedException {
        AdmissionResponse created = admissionService.createAdmissionRequest(
                new AdmissionCreationRequest("PAT-MR-C", PatientGender.MALE, WardType.GENERAL,
                        BedType.STANDARD, AdmissionPriority.NORMAL, false));
        assertEquals(AdmissionStatus.RESERVED, created.status());

        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(2);
        List<String> failures = java.util.Collections.synchronizedList(new java.util.ArrayList<>());

        for (int i = 0; i < 2; i++) {
            pool.submit(() -> {
                try {
                    start.await();
                    admissionService.confirmAdmission(created.admissionId());
                } catch (Exception ex) {
                    failures.add(ex.getClass().getSimpleName());
                } finally {
                    done.countDown();
                }
            });
        }

        start.countDown();
        assertTrue(done.await(60, TimeUnit.SECONDS));
        pool.shutdown();
        assertTrue(pool.awaitTermination(30, TimeUnit.SECONDS));

        assertEquals(1, failures.size(), "Second confirm must be rejected, got failures: " + failures);
        assertEquals(AdmissionStatus.ADMITTED,
                admissionRepository.findById(created.admissionId()).orElseThrow().getStatus());
        assertEquals(BedStatus.OCCUPIED,
                bedRepository.findById(bed.getId()).orElseThrow().getStatus());
    }
}