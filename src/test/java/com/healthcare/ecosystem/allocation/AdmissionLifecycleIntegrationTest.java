package com.healthcare.ecosystem.allocation;

import com.healthcare.ecosystem.allocation.dto.request.AdmissionCreationRequest;
import com.healthcare.ecosystem.allocation.dto.response.AdmissionResponse;
import com.healthcare.ecosystem.allocation.model.entity.*;
import com.healthcare.ecosystem.allocation.model.enums.*;
import com.healthcare.ecosystem.allocation.repository.*;
import com.healthcare.ecosystem.allocation.service.AdmissionService;
import com.healthcare.ecosystem.allocation.service.BedManagementService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.*;

/**
 * End-to-end lifecycle coverage on a real (H2) datasource:
 * admission -> reserve -> confirm -> discharge -> maintenance -> waiting-list hand-off.
 */
@SpringBootTest
@ActiveProfiles("test")
class AdmissionLifecycleIntegrationTest {

    @Autowired
    private AdmissionService admissionService;

    @Autowired
    private BedManagementService bedManagementService;

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

    private Hospital hospital;
    private Room room;
    private Bed bed;

    @BeforeEach
    void setUp() {
        // Children before parents: waiting_list -> admissions, bed_status_logs -> beds.
        // (H2 DDL from entities has no ON DELETE CASCADE, so parent-first deletes
        // violate the FK as soon as any previous test left a queue/audit row behind.)
        waitingListRepository.deleteAll();
        bedStatusLogRepository.deleteAll();
        admissionRepository.deleteAll();
        bedRepository.deleteAll();
        roomRepository.deleteAll();
        wardRepository.deleteAll();
        hospitalRepository.deleteAll();

        hospital = hospitalRepository.save(new Hospital("H-LIFE", "Lifecycle Hospital", "Street 1"));
        Ward ward = wardRepository.save(
                new Ward(hospital, "General Ward", WardType.GENERAL, GenderPolicy.UNISEX));
        room = roomRepository.save(new Room(ward, "G-100", false));
        bed = bedRepository.save(new Bed(room, "G-BED-1", BedType.STANDARD));
    }

    @Test
    @DisplayName("Full happy path: REQUEST -> RESERVED -> ADMITTED -> DISCHARGED -> AVAILABLE")
    void fullLifecycle() {
        AdmissionResponse created = admissionService.createAdmissionRequest(
                new AdmissionCreationRequest("PAT-1", PatientGender.MALE, WardType.GENERAL,
                        BedType.STANDARD, AdmissionPriority.NORMAL, false));

        assertEquals(AdmissionStatus.RESERVED, created.status());
        assertNotNull(created.allocatedBedId());
        assertNotNull(created.reservationExpiresAt());

        AdmissionResponse confirmed = admissionService.confirmAdmission(created.admissionId());
        assertEquals(AdmissionStatus.ADMITTED, confirmed.status());
        assertNotNull(confirmed.admissionTime());
        assertNull(confirmed.reservationExpiresAt());

        AdmissionResponse discharged = admissionService.dischargeAdmission(created.admissionId());
        assertEquals(AdmissionStatus.DISCHARGED, discharged.status());
        assertNotNull(discharged.dischargeTime());
        assertEquals(BedStatus.MAINTENANCE, bedRepository.findById(bed.getId()).orElseThrow().getStatus());

        var released = bedManagementService.completeMaintenance(bed.getId());
        assertEquals(BedStatus.AVAILABLE, released.status());
    }

    @Test
    @DisplayName("Second admission for the only bed goes to the waiting list, "
            + "and is served when the bed is released via maintenance")
    void waitingListIsServedOnMaintenanceCompletion() {
        AdmissionResponse first = admissionService.createAdmissionRequest(
                new AdmissionCreationRequest("PAT-A", PatientGender.MALE, WardType.GENERAL,
                        BedType.STANDARD, AdmissionPriority.NORMAL, false));
        assertEquals(AdmissionStatus.RESERVED, first.status());

        AdmissionResponse second = admissionService.createAdmissionRequest(
                new AdmissionCreationRequest("PAT-B", PatientGender.MALE, WardType.GENERAL,
                        BedType.STANDARD, AdmissionPriority.URGENT, false));
        assertEquals(AdmissionStatus.WAITING_LIST, second.status());
        assertEquals(1, waitingListRepository.count());

        // PAT-A arrives and is discharged -> bed parked in MAINTENANCE
        admissionService.confirmAdmission(first.admissionId());
        admissionService.dischargeAdmission(first.admissionId());

        // Housekeeping releases the bed -> the waiting patient is served automatically
        bedManagementService.completeMaintenance(bed.getId());

        AdmissionResponse served = admissionService.getAdmissionById(second.admissionId());
        assertEquals(AdmissionStatus.RESERVED, served.status(), "Waiting patient must auto-reserve the freed bed");
        assertEquals(bed.getId(), served.allocatedBedId());
        assertEquals(0, waitingListRepository.count(), "Waiting entry must be dequeued");
    }

    @Test
    @DisplayName("Discharge is rejected unless the admission is ADMITTED")
    void dischargeFromWrongStateIsRejected() {
        AdmissionResponse created = admissionService.createAdmissionRequest(
                new AdmissionCreationRequest("PAT-C", PatientGender.FEMALE, WardType.GENERAL,
                        BedType.STANDARD, AdmissionPriority.NORMAL, false));

        assertThrows(RuntimeException.class, () -> admissionService.dischargeAdmission(created.admissionId()));
    }

    @Test
    @DisplayName("Cancelling a reservation releases the bed back for the next patient")
    void cancelReleasesBed() {
        AdmissionResponse first = admissionService.createAdmissionRequest(
                new AdmissionCreationRequest("PAT-D", PatientGender.MALE, WardType.GENERAL,
                        BedType.STANDARD, AdmissionPriority.NORMAL, false));
        assertEquals(AdmissionStatus.RESERVED, first.status());

        AdmissionResponse cancelled = admissionService.cancelAdmission(first.admissionId(), "Patient left");
        assertEquals(AdmissionStatus.CANCELLED, cancelled.status());
        assertNull(cancelled.allocatedBedId());
        assertEquals(BedStatus.AVAILABLE, bedRepository.findById(bed.getId()).orElseThrow().getStatus());

        // Bed is free again for a brand new request
        AdmissionResponse next = admissionService.createAdmissionRequest(
                new AdmissionCreationRequest("PAT-E", PatientGender.MALE, WardType.GENERAL,
                        BedType.STANDARD, AdmissionPriority.NORMAL, false));
        assertEquals(AdmissionStatus.RESERVED, next.status());
    }

    @Test
    @DisplayName("A female patient cannot take the UNISEX general bed via manual reservation "
            + "when the ward is FEMALE_ONLY - covered by engine unit tests; here we assert "
            + "that manual reservation of an ineligible bed is rejected")
    void manualReservationRejectsIneligibleBed() {
        AdmissionResponse created = admissionService.createAdmissionRequest(
                new AdmissionCreationRequest("PAT-F", PatientGender.MALE, WardType.PEDIATRIC,
                        BedType.ICU, AdmissionPriority.NORMAL, false));
        assertEquals(AdmissionStatus.WAITING_LIST, created.status());

        assertThrows(RuntimeException.class,
                () -> admissionService.reserveBedManually(created.admissionId(), bed.getId()));
    }

    @Test
    @DisplayName("Bed status audit trail records every transition")
    void auditTrailIsWritten() {
        AdmissionResponse created = admissionService.createAdmissionRequest(
                new AdmissionCreationRequest("PAT-G", PatientGender.MALE, WardType.GENERAL,
                        BedType.STANDARD, AdmissionPriority.NORMAL, false));
        admissionService.confirmAdmission(created.admissionId());
        admissionService.dischargeAdmission(created.admissionId());
        bedManagementService.completeMaintenance(bed.getId());

        var logs = bedStatusLogRepository.findByBedIdOrderByLoggedAtAsc(bed.getId());
        assertTrue(logs.size() >= 4, "Expected AVAILABLE->RESERVED->OCCUPIED->MAINTENANCE->AVAILABLE trail");

        var actors = logs.stream().map(BedStatusLog::getChangedBy).collect(Collectors.toSet());
        assertTrue(actors.contains("ALLOCATION_ENGINE"), "engine allocation must be logged");
        assertTrue(actors.contains("ADMISSION_CONFIRM"), "confirm transition must be logged");
        assertTrue(actors.contains("DISCHARGE_WORKFLOW"), "discharge transition must be logged");
        assertTrue(actors.contains("MAINTENANCE_SUPERVISOR"), "maintenance release must be logged");

        var newStatuses = logs.stream().map(BedStatusLog::getNewStatus).collect(Collectors.toSet());
        assertEquals(Set.of(BedStatus.AVAILABLE, BedStatus.RESERVED, BedStatus.OCCUPIED, BedStatus.MAINTENANCE),
                newStatuses, "All four bed states must appear in the trail");
    }

    @Test
    @DisplayName("Isolation patients only get isolation rooms, non-isolation patients never do")
    void isolationPolicyIsEnforcedEndToEnd() {
        Ward isolationWard = wardRepository.save(
                new Ward(hospital, "Isolation Ward", WardType.ICU, GenderPolicy.UNISEX));
        Room isolationRoom = roomRepository.save(new Room(isolationWard, "ISO-1", true));
        Bed isolationBed = bedRepository.save(new Bed(isolationRoom, "ISO-BED-1", BedType.ICU));

        AdmissionResponse needsIsolation = admissionService.createAdmissionRequest(
                new AdmissionCreationRequest("PAT-ISO", PatientGender.MALE, WardType.ICU,
                        BedType.ICU, AdmissionPriority.EMERGENCY, true));
        assertEquals(AdmissionStatus.RESERVED, needsIsolation.status());
        assertEquals(isolationBed.getId(),
                admissionService.getAdmissionById(needsIsolation.admissionId()).allocatedBedId(),
                "Isolation patient must land in the isolation bed");

        AdmissionResponse noIsolation = admissionService.createAdmissionRequest(
                new AdmissionCreationRequest("PAT-NORM", PatientGender.MALE, WardType.ICU,
                        BedType.ICU, AdmissionPriority.EMERGENCY, false));
        assertEquals(AdmissionStatus.WAITING_LIST, noIsolation.status(),
                "Non-isolation patient must never occupy the only isolation bed");
    }
}