package com.healthcare.ecosystem.allocation;

import com.healthcare.ecosystem.allocation.dto.request.AdmissionCreationRequest;
import com.healthcare.ecosystem.allocation.dto.request.CreateWardRequest;
import com.healthcare.ecosystem.allocation.dto.response.AdmissionResponse;
import com.healthcare.ecosystem.allocation.model.entity.Admission;
import com.healthcare.ecosystem.allocation.model.entity.Bed;
import com.healthcare.ecosystem.allocation.model.entity.Hospital;
import com.healthcare.ecosystem.allocation.model.entity.Room;
import com.healthcare.ecosystem.allocation.model.entity.Ward;
import com.healthcare.ecosystem.allocation.model.enums.*;
import com.healthcare.ecosystem.allocation.repository.*;
import com.healthcare.ecosystem.allocation.scheduler.ReservationExpiryScheduler;
import com.healthcare.ecosystem.allocation.service.AdmissionService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Covers the reservation-expiry path that the scheduled sweep drives.
 *
 * The sweep itself is invoked directly rather than waited for: the test profile parks the
 * production cron, and a 1-minute real-time wait would be both slow and flaky. What matters
 * is the reclaim logic the cron calls.
 */
@SpringBootTest
@ActiveProfiles("test")
class ReservationExpiryIntegrationTest {

    @Autowired
    private AdmissionService admissionService;

    @Autowired
    private ReservationExpiryScheduler scheduler;

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

        Hospital hospital = hospitalRepository.save(new Hospital("H-EXP", "Expiry Hospital", "Street 9"));
        Ward ward = wardRepository.save(
                new Ward(hospital, "Expiry Ward", WardType.GENERAL, GenderPolicy.UNISEX));
        Room room = roomRepository.save(new Room(ward, "E-1", false));
        bed = bedRepository.save(new Bed(room, "E-BED-1", BedType.STANDARD));
    }

    /**
     * Backdates the reservation expiry so the sweep sees it as elapsed.
     * {@code updated_at} is not touched by this, keeping the entity's audit column honest.
     */
    private void backdateReservation(Long admissionId) {
        Admission admission = admissionRepository.findById(admissionId).orElseThrow();
        admission.setReservationExpiresAt(Instant.now().minus(5, ChronoUnit.MINUTES));
        admissionRepository.saveAndFlush(admission);
    }

    @Test
    @DisplayName("Reservation expiry: RESERVED -> EXPIRED, bed reclaimed to AVAILABLE")
    void expiredReservationIsReclaimed() {
        AdmissionResponse created = admissionService.createAdmissionRequest(
                new AdmissionCreationRequest("PAT-EXP-1", PatientGender.MALE, WardType.GENERAL,
                        BedType.STANDARD, AdmissionPriority.NORMAL, false));
        assertEquals(AdmissionStatus.RESERVED, created.status());
        assertEquals(BedStatus.RESERVED,
                bedRepository.findById(bed.getId()).orElseThrow().getStatus());

        backdateReservation(created.admissionId());
        scheduler.processExpiredReservations();

        Admission after = admissionRepository.findById(created.admissionId()).orElseThrow();
        assertEquals(AdmissionStatus.EXPIRED, after.getStatus(), "Admission must be EXPIRED");
        assertNull(after.getAllocatedBed(), "Expired admission must not keep a bed reference");
        assertNotNull(after.getCancellationReason());

        assertEquals(BedStatus.AVAILABLE,
                bedRepository.findById(bed.getId()).orElseThrow().getStatus(),
                "Bed must be released back to AVAILABLE");
    }

    @Test
    @DisplayName("Reservation expiry is audited with the EXPIRY_SCHEDULER actor")
    void expiryIsAudited() {
        AdmissionResponse created = admissionService.createAdmissionRequest(
                new AdmissionCreationRequest("PAT-EXP-2", PatientGender.MALE, WardType.GENERAL,
                        BedType.STANDARD, AdmissionPriority.NORMAL, false));
        backdateReservation(created.admissionId());

        scheduler.processExpiredReservations();

        var logs = bedStatusLogRepository.findByBedIdOrderByLoggedAtAsc(bed.getId());
        var actors = logs.stream().map(l -> l.getChangedBy()).toList();
        assertTrue(actors.contains("EXPIRY_SCHEDULER"),
                "Reclaim must be recorded in the audit trail, got: " + actors);
    }

    @Test
    @DisplayName("An unexpired reservation is NOT swept")
    void liveReservationIsLeftAlone() {
        AdmissionResponse created = admissionService.createAdmissionRequest(
                new AdmissionCreationRequest("PAT-EXP-3", PatientGender.MALE, WardType.GENERAL,
                        BedType.STANDARD, AdmissionPriority.NORMAL, false));
        // reservationExpiresAt is still in the future

        scheduler.processExpiredReservations();

        Admission after = admissionRepository.findById(created.admissionId()).orElseThrow();
        assertEquals(AdmissionStatus.RESERVED, after.getStatus(), "Live hold must survive the sweep");
        assertEquals(BedStatus.RESERVED,
                bedRepository.findById(bed.getId()).orElseThrow().getStatus());
    }

    @Test
    @DisplayName("An ADMITTED patient is never expired by the sweep")
    void admittedPatientIsNotExpired() {
        AdmissionResponse created = admissionService.createAdmissionRequest(
                new AdmissionCreationRequest("PAT-EXP-4", PatientGender.MALE, WardType.GENERAL,
                        BedType.STANDARD, AdmissionPriority.NORMAL, false));
        admissionService.confirmAdmission(created.admissionId());
        assertEquals(AdmissionStatus.ADMITTED,
                admissionRepository.findById(created.admissionId()).orElseThrow().getStatus());

        scheduler.processExpiredReservations();

        Admission after = admissionRepository.findById(created.admissionId()).orElseThrow();
        assertEquals(AdmissionStatus.ADMITTED, after.getStatus(),
                "A confirmed admission must never be expired");
        assertEquals(BedStatus.OCCUPIED,
                bedRepository.findById(bed.getId()).orElseThrow().getStatus(),
                "An occupied bed must not be released by the sweep");
    }

    @Test
    @DisplayName("Expired bed is immediately re-offered to the waiting list")
    void expiredBedIsHandedToWaitingPatient() {
        // Patient 1 takes the only bed
        AdmissionResponse first = admissionService.createAdmissionRequest(
                new AdmissionCreationRequest("PAT-EXP-A", PatientGender.MALE, WardType.GENERAL,
                        BedType.STANDARD, AdmissionPriority.NORMAL, false));
        assertEquals(AdmissionStatus.RESERVED, first.status());

        // Patient 2 queues
        AdmissionResponse second = admissionService.createAdmissionRequest(
                new AdmissionCreationRequest("PAT-EXP-B", PatientGender.MALE, WardType.GENERAL,
                        BedType.STANDARD, AdmissionPriority.EMERGENCY, false));
        assertEquals(AdmissionStatus.WAITING_LIST, second.status());

        // Patient 1 never arrives; the hold lapses
        backdateReservation(first.admissionId());
        scheduler.processExpiredReservations();

        assertEquals(AdmissionStatus.EXPIRED,
                admissionRepository.findById(first.admissionId()).orElseThrow().getStatus());

        AdmissionResponse served = admissionService.getAdmissionById(second.admissionId());
        assertEquals(AdmissionStatus.RESERVED, served.status(),
                "The waiting EMERGENCY patient must inherit the reclaimed bed");
        assertEquals(bed.getId(), served.allocatedBedId());
        assertEquals(0, waitingListRepository.count(), "Waiting entry must be dequeued");
    }

    @Test
    @DisplayName("Sweeping with nothing expired is a no-op")
    void sweepWithNoExpiredReservationsIsNoop() {
        long bedsBefore = bedRepository.count();

        scheduler.processExpiredReservations();

        assertEquals(bedsBefore, bedRepository.count());
        assertEquals(BedStatus.AVAILABLE,
                bedRepository.findById(bed.getId()).orElseThrow().getStatus());
    }
}