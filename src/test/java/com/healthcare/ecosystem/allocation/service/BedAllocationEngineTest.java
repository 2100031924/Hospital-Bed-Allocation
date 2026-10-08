package com.healthcare.ecosystem.allocation.service;

import com.healthcare.ecosystem.allocation.model.entity.Admission;
import com.healthcare.ecosystem.allocation.model.entity.Bed;
import com.healthcare.ecosystem.allocation.model.entity.Hospital;
import com.healthcare.ecosystem.allocation.model.entity.Room;
import com.healthcare.ecosystem.allocation.model.entity.Ward;
import com.healthcare.ecosystem.allocation.model.enums.*;
import com.healthcare.ecosystem.allocation.repository.AdmissionRepository;
import com.healthcare.ecosystem.allocation.repository.BedRepository;
import com.healthcare.ecosystem.allocation.service.impl.BedAllocationEngineImpl;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.ConcurrencyFailureException;

import java.lang.reflect.Field;
import java.util.Collections;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class BedAllocationEngineTest {

    @Mock
    private BedRepository bedRepository;

    @Mock
    private AdmissionRepository admissionRepository;

    @InjectMocks
    private BedAllocationEngineImpl allocationEngine;

    private Hospital hospital;
    private Ward icuWard;
    private Ward femaleWard;
    private Room singleIsolationRoom;
    private Room multiBedRoom;
    private Bed standardBed;

    /**
     * Unit tests build unpersisted entities, so every id is null by default. Allocation rule 2
     * ("existing reservation") and rules 6-7 query by bed/room id, so entities that must be
     * distinguishable get deterministic ids here. Without this, two beds in the same room would
     * both be looked up under id=null and a stub meant for bed A would also match bed B.
     */
    private static void assignId(Object entity, Long id) {
        try {
            Field field = entity.getClass().getDeclaredField("id");
            field.setAccessible(true);
            field.set(entity, id);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("Could not assign id for unit test", e);
        }
    }

    @BeforeEach
    void setUp() {
        hospital = new Hospital("HOSP-01", "Central Hospital", "Downtown");
        assignId(hospital, 1L);

        icuWard = new Ward(hospital, "ICU Ward", WardType.ICU, GenderPolicy.UNISEX);
        assignId(icuWard, 10L);
        femaleWard = new Ward(hospital, "Maternity Ward", WardType.MATERNITY, GenderPolicy.FEMALE_ONLY);
        assignId(femaleWard, 11L);

        singleIsolationRoom = new Room(icuWard, "ISO-101", true);
        assignId(singleIsolationRoom, 20L);
        multiBedRoom = new Room(femaleWard, "MAT-201", false);
        assignId(multiBedRoom, 21L);

        standardBed = new Bed(singleIsolationRoom, "B-1", BedType.ICU);
        assignId(standardBed, 30L);
    }

    // ------------------------------------------------------------------
    // isBedEligibleForAdmission - null safety
    // ------------------------------------------------------------------

    @Test
    @DisplayName("Should reject when bed is null")
    void shouldRejectNullBed() {
        Admission admission = new Admission("P-000", PatientGender.MALE, WardType.ICU,
                BedType.ICU, AdmissionPriority.NORMAL, false);

        assertFalse(allocationEngine.isBedEligibleForAdmission(null, admission));
        verifyNoInteractions(bedRepository, admissionRepository);
    }

    @Test
    @DisplayName("Should reject when admission is null")
    void shouldRejectNullAdmission() {
        assertFalse(allocationEngine.isBedEligibleForAdmission(standardBed, null));
        verifyNoInteractions(bedRepository, admissionRepository);
    }

    @Test
    @DisplayName("Should reject when bed has no room")
    void shouldRejectBedWithoutRoom() {
        Bed orphanBed = new Bed(singleIsolationRoom, "ORPHAN", BedType.ICU);
        assignId(orphanBed, 31L);
        orphanBed.setRoom(null);
        Admission admission = new Admission("P-001", PatientGender.FEMALE, WardType.ICU,
                BedType.ICU, AdmissionPriority.NORMAL, true);

        assertFalse(allocationEngine.isBedEligibleForAdmission(orphanBed, admission));
        verifyNoInteractions(bedRepository, admissionRepository);
    }

    @Test
    @DisplayName("Should reject when room has no ward")
    void shouldRejectRoomWithoutWard() {
        Room wardlessRoom = new Room(icuWard, "NOWARD-1", false);
        assignId(wardlessRoom, 22L);
        wardlessRoom.setWard(null);
        Bed bed = new Bed(wardlessRoom, "NW-1", BedType.ICU);
        assignId(bed, 32L);
        Admission admission = new Admission("P-002", PatientGender.FEMALE, WardType.ICU,
                BedType.ICU, AdmissionPriority.NORMAL, false);

        assertFalse(allocationEngine.isBedEligibleForAdmission(bed, admission));
        verifyNoInteractions(bedRepository, admissionRepository);
    }

    // ------------------------------------------------------------------
    // isBedEligibleForAdmission - status / reservation rules
    // ------------------------------------------------------------------

    @Test
    @DisplayName("Should reject a bed whose status is not AVAILABLE")
    void shouldRejectNonAvailableBed() {
        standardBed.setStatus(BedStatus.MAINTENANCE);
        Admission admission = new Admission("P-303", PatientGender.MALE, WardType.ICU,
                BedType.ICU, AdmissionPriority.NORMAL, false);

        boolean eligible = allocationEngine.isBedEligibleForAdmission(standardBed, admission);
        assertFalse(eligible, "Only AVAILABLE beds are allocatable");
        verifyNoInteractions(bedRepository, admissionRepository);
    }

    @Test
    @DisplayName("Should reject every non-AVAILABLE status (BLOCKED, RESERVED, OCCUPIED, MAINTENANCE)")
    void shouldRejectAllNonAvailableStatuses() {
        Admission admission = new Admission("P-304", PatientGender.MALE, WardType.ICU,
                BedType.ICU, AdmissionPriority.NORMAL, false);

        for (BedStatus status : List.of(BedStatus.BLOCKED, BedStatus.RESERVED,
                BedStatus.OCCUPIED, BedStatus.MAINTENANCE)) {
            standardBed.setStatus(status);
            assertFalse(allocationEngine.isBedEligibleForAdmission(standardBed, admission),
                    "Status " + status + " must not be allocatable");
        }
        verifyNoInteractions(bedRepository, admissionRepository);
    }

    @Test
    @DisplayName("Should reject a bed already held by a live admission (rule 2)")
    void shouldRejectAlreadyHeldBed() {
        Bed heldBed = new Bed(singleIsolationRoom, "HELD-1", BedType.ICU);
        assignId(heldBed, 33L);
        Admission applicant = new Admission("P-305", PatientGender.FEMALE, WardType.ICU,
                BedType.ICU, AdmissionPriority.URGENT, true);
        Admission holder = new Admission("P-HOLDER", PatientGender.FEMALE, WardType.ICU,
                BedType.ICU, AdmissionPriority.NORMAL, true);
        holder.setStatus(AdmissionStatus.RESERVED);

        when(admissionRepository.findByAllocatedBedIdAndStatusIn(eq(heldBed.getId()), anyList()))
                .thenReturn(Optional.of(holder));

        assertFalse(allocationEngine.isBedEligibleForAdmission(heldBed, applicant),
                "A bed with a live RESERVED admission must not be re-issued");
    }

    // ------------------------------------------------------------------
    // isBedEligibleForAdmission - ward / bed type / gender rules
    // ------------------------------------------------------------------

    @Test
    @DisplayName("Should reject allocation when ward gender policy mismatches patient gender")
    void shouldRejectWhenGenderMismatchesWardPolicy() {
        Bed femaleBed = new Bed(multiBedRoom, "F-1", BedType.STANDARD);
        assignId(femaleBed, 34L);
        Admission maleAdmission = new Admission("P-999", PatientGender.MALE, WardType.MATERNITY,
                BedType.STANDARD, AdmissionPriority.NORMAL, false);

        boolean eligible = allocationEngine.isBedEligibleForAdmission(femaleBed, maleAdmission);
        assertFalse(eligible, "Male patient must not be admitted into FEMALE_ONLY ward");
    }

    @Test
    @DisplayName("Should reject when ward type or bed type does not match the request")
    void shouldRejectMismatchedWardOrBedType() {
        Room generalRoom = new Room(new Ward(hospital, "Gen", WardType.GENERAL, GenderPolicy.UNISEX),
                "G-1", false);
        assignId(generalRoom, 23L);
        Bed generalBed = new Bed(generalRoom, "GB-1", BedType.STANDARD);
        assignId(generalBed, 35L);

        Admission icuAdmission = new Admission("P-404", PatientGender.MALE, WardType.ICU,
                BedType.ICU, AdmissionPriority.NORMAL, false);

        boolean eligible = allocationEngine.isBedEligibleForAdmission(generalBed, icuAdmission);
        assertFalse(eligible, "Ward type mismatch must be rejected");
    }

    // ------------------------------------------------------------------
    // isBedEligibleForAdmission - isolation rules
    // ------------------------------------------------------------------

    @Test
    @DisplayName("Should reject non-isolation patient from taking isolation-flagged room")
    void shouldRejectNonIsolationPatientFromIsolationRoom() {
        Admission normalAdmission = new Admission("P-101", PatientGender.FEMALE, WardType.ICU,
                BedType.ICU, AdmissionPriority.NORMAL, false);

        // Rule 2 (existing reservation) runs before rule 6 (isolation), so the bed must
        // look unheld for the test to reach the isolation check.
        when(admissionRepository.findByAllocatedBedIdAndStatusIn(eq(standardBed.getId()), anyList()))
                .thenReturn(Optional.empty());

        boolean eligible = allocationEngine.isBedEligibleForAdmission(standardBed, normalAdmission);
        assertFalse(eligible, "Non-isolation patient should not consume dedicated isolation room");
        // Rule 6 rejects before any bedRepository query (no room-occupancy scan needed).
        verifyNoInteractions(bedRepository);
    }

    @Test
    @DisplayName("Should approve isolation patient in clean isolation room")
    void shouldApproveIsolationPatientInAvailableIsolationRoom() {
        Admission isolationAdmission = new Admission("P-202", PatientGender.FEMALE, WardType.ICU,
                BedType.ICU, AdmissionPriority.URGENT, true);

        when(bedRepository.findActiveBedsInRoom(singleIsolationRoom.getId()))
                .thenReturn(Collections.emptyList());

        boolean eligible = allocationEngine.isBedEligibleForAdmission(standardBed, isolationAdmission);
        assertTrue(eligible, "Isolation patient should be allowed in available isolation room");
    }

    @Test
    @DisplayName("Should reject isolation patient when isolation room already has an active bed")
    void shouldRejectIsolationPatientWhenRoomOccupied() {
        Admission isolationAdmission = new Admission("P-203", PatientGender.FEMALE, WardType.ICU,
                BedType.ICU, AdmissionPriority.EMERGENCY, true);

        Bed otherBed = new Bed(singleIsolationRoom, "B-2", BedType.ICU);
        assignId(otherBed, 36L);
        when(bedRepository.findActiveBedsInRoom(singleIsolationRoom.getId()))
                .thenReturn(List.of(otherBed));

        boolean eligible = allocationEngine.isBedEligibleForAdmission(standardBed, isolationAdmission);
        assertFalse(eligible, "Isolation rooms must not be shared");
    }

    // ------------------------------------------------------------------
    // isBedEligibleForAdmission - co-occupancy rule
    // ------------------------------------------------------------------

    @Test
    @DisplayName("Should reject patient when multi-bed room has opposite gender occupant")
    void shouldRejectCoedInMultiBedRoom() {
        Ward unisexGeneralWard = new Ward(hospital, "General Ward", WardType.GENERAL, GenderPolicy.UNISEX);
        assignId(unisexGeneralWard, 12L);
        Room sharedRoom = new Room(unisexGeneralWard, "SHARED-1", false);
        assignId(sharedRoom, 24L);
        Bed bedA = new Bed(sharedRoom, "BED-A", BedType.STANDARD);
        Bed bedB = new Bed(sharedRoom, "BED-B", BedType.STANDARD);
        assignId(bedA, 1L);
        assignId(bedB, 2L);

        Admission femaleApplicant = new Admission("P-FEMALE", PatientGender.FEMALE, WardType.GENERAL,
                BedType.STANDARD, AdmissionPriority.NORMAL, false);

        // Bed A is already OCCUPIED by a MALE
        Admission maleResident = new Admission("P-MALE", PatientGender.MALE, WardType.GENERAL,
                BedType.STANDARD, AdmissionPriority.NORMAL, false);
        maleResident.setStatus(AdmissionStatus.ADMITTED);

        when(bedRepository.findActiveBedsInRoom(sharedRoom.getId())).thenReturn(List.of(bedA));
        // Only bed A is held; bed B must look unheld so rule 2 passes and rule 7 does the work.
        when(admissionRepository.findByAllocatedBedIdAndStatusIn(eq(bedA.getId()), anyList()))
                .thenReturn(Optional.of(maleResident));
        when(admissionRepository.findByAllocatedBedIdAndStatusIn(eq(bedB.getId()), anyList()))
                .thenReturn(Optional.empty());

        boolean eligible = allocationEngine.isBedEligibleForAdmission(bedB, femaleApplicant);
        assertFalse(eligible, "Female applicant cannot be placed in a room currently occupied by a male patient");
    }

    @Test
    @DisplayName("Should approve same-gender co-placement in a multi-bed non-isolation room")
    void shouldApproveSameGenderCoPlacement() {
        Ward unisexGeneralWard = new Ward(hospital, "General Ward", WardType.GENERAL, GenderPolicy.UNISEX);
        assignId(unisexGeneralWard, 13L);
        Room sharedRoom = new Room(unisexGeneralWard, "SHARED-2", false);
        assignId(sharedRoom, 25L);
        Bed bedA = new Bed(sharedRoom, "BED-A", BedType.STANDARD);
        Bed bedB = new Bed(sharedRoom, "BED-B", BedType.STANDARD);
        assignId(bedA, 1L);
        assignId(bedB, 2L);

        Admission maleApplicant = new Admission("P-M2", PatientGender.MALE, WardType.GENERAL,
                BedType.STANDARD, AdmissionPriority.NORMAL, false);

        Admission maleResident = new Admission("P-M1", PatientGender.MALE, WardType.GENERAL,
                BedType.STANDARD, AdmissionPriority.NORMAL, false);
        maleResident.setStatus(AdmissionStatus.ADMITTED);

        when(bedRepository.findActiveBedsInRoom(sharedRoom.getId())).thenReturn(List.of(bedA));
        when(admissionRepository.findByAllocatedBedIdAndStatusIn(eq(bedA.getId()), anyList()))
                .thenReturn(Optional.of(maleResident));
        when(admissionRepository.findByAllocatedBedIdAndStatusIn(eq(bedB.getId()), anyList()))
                .thenReturn(Optional.empty());

        boolean eligible = allocationEngine.isBedEligibleForAdmission(bedB, maleApplicant);
        assertTrue(eligible, "Same-gender co-placement is allowed");
    }

    // ------------------------------------------------------------------
    // isBedAlreadyHeld
    // ------------------------------------------------------------------

    @Test
    @DisplayName("isBedAlreadyHeld should return false for null bed or null id without querying")
    void shouldReturnFalseForNullBedOrNullId() {
        assertFalse(allocationEngine.isBedAlreadyHeld(null));

        Bed transientBed = new Bed(singleIsolationRoom, "T-1", BedType.ICU);
        assertFalse(allocationEngine.isBedAlreadyHeld(transientBed));

        verifyNoInteractions(admissionRepository);
    }

    @Test
    @DisplayName("isBedAlreadyHeld should return true when a live admission holds the bed")
    void shouldReturnTrueWhenHeld() {
        Bed bed = new Bed(singleIsolationRoom, "H-1", BedType.ICU);
        assignId(bed, 40L);
        Admission holder = new Admission("P-H", PatientGender.MALE, WardType.ICU,
                BedType.ICU, AdmissionPriority.NORMAL, false);
        holder.setStatus(AdmissionStatus.ADMITTED);

        when(admissionRepository.findByAllocatedBedIdAndStatusIn(eq(bed.getId()), anyList()))
                .thenReturn(Optional.of(holder));

        assertTrue(allocationEngine.isBedAlreadyHeld(bed));
    }

    @Test
    @DisplayName("isBedAlreadyHeld should return false when no live admission holds the bed")
    void shouldReturnFalseWhenFree() {
        Bed bed = new Bed(singleIsolationRoom, "F-1", BedType.ICU);
        assignId(bed, 41L);

        when(admissionRepository.findByAllocatedBedIdAndStatusIn(eq(bed.getId()), anyList()))
                .thenReturn(Optional.empty());

        assertFalse(allocationEngine.isBedAlreadyHeld(bed));
    }

    // ------------------------------------------------------------------
    // allocateBed
    // ------------------------------------------------------------------

    @Test
    @DisplayName("allocateBed should return empty for null admission without querying")
    void shouldReturnEmptyForNullAdmission() {
        Optional<Bed> result = allocationEngine.allocateBed(null);

        assertTrue(result.isEmpty());
        verifyNoInteractions(bedRepository, admissionRepository);
    }

    @Test
    @DisplayName("allocateBed should return empty when no candidate beds exist")
    void shouldReturnEmptyWhenNoCandidates() {
        Admission admission = new Admission("P-500", PatientGender.MALE, WardType.ICU,
                BedType.ICU, AdmissionPriority.NORMAL, false);

        when(bedRepository.findEligibleCandidateBedIds(
                WardType.ICU, BedType.ICU, false))
                .thenReturn(Collections.emptyList());

        Optional<Bed> result = allocationEngine.allocateBed(admission);

        assertTrue(result.isEmpty());
        verify(bedRepository, never()).findByIdWithPessimisticLock(any());
    }

    @Test
    @DisplayName("allocateBed should lock and return the first eligible bed")
    void shouldAllocateFirstEligibleBed() {
        Ward ward = new Ward(hospital, "General", WardType.GENERAL, GenderPolicy.UNISEX);
        assignId(ward, 14L);
        Room room = new Room(ward, "G-10", false);
        assignId(room, 26L);
        Bed bed = new Bed(room, "G-BED-10", BedType.STANDARD);
        assignId(bed, 50L);
        Admission admission = new Admission("P-501", PatientGender.MALE, WardType.GENERAL,
                BedType.STANDARD, AdmissionPriority.NORMAL, false);

        when(bedRepository.findEligibleCandidateBedIds(
                WardType.GENERAL, BedType.STANDARD, false))
                .thenReturn(List.of(bed.getId()));
        when(bedRepository.findByIdWithPessimisticLock(bed.getId()))
                .thenReturn(Optional.of(bed));
        when(admissionRepository.findByAllocatedBedIdAndStatusIn(eq(bed.getId()), anyList()))
                .thenReturn(Optional.empty());
        when(bedRepository.findActiveBedsInRoom(room.getId()))
                .thenReturn(Collections.emptyList());

        Optional<Bed> result = allocationEngine.allocateBed(admission);

        assertTrue(result.isPresent());
        assertEquals(bed.getId(), result.get().getId());
    }

    @Test
    @DisplayName("allocateBed should skip a lock-contended candidate and take the next one")
    void shouldSkipLockContendedCandidate() {
        Ward ward = new Ward(hospital, "General", WardType.GENERAL, GenderPolicy.UNISEX);
        assignId(ward, 15L);
        Room room = new Room(ward, "G-11", false);
        assignId(room, 27L);
        Bed contendedBed = new Bed(room, "G-BED-11A", BedType.STANDARD);
        assignId(contendedBed, 51L);
        Bed freeBed = new Bed(room, "G-BED-11B", BedType.STANDARD);
        assignId(freeBed, 52L);
        Admission admission = new Admission("P-502", PatientGender.MALE, WardType.GENERAL,
                BedType.STANDARD, AdmissionPriority.NORMAL, false);

        when(bedRepository.findEligibleCandidateBedIds(
                WardType.GENERAL, BedType.STANDARD, false))
                .thenReturn(List.of(contendedBed.getId(), freeBed.getId()));
        when(bedRepository.findByIdWithPessimisticLock(contendedBed.getId()))
                .thenThrow(new ConcurrencyFailureException("row locked by concurrent transaction"));
        when(bedRepository.findByIdWithPessimisticLock(freeBed.getId()))
                .thenReturn(Optional.of(freeBed));
        when(admissionRepository.findByAllocatedBedIdAndStatusIn(eq(freeBed.getId()), anyList()))
                .thenReturn(Optional.empty());
        when(bedRepository.findActiveBedsInRoom(room.getId()))
                .thenReturn(Collections.emptyList());

        Optional<Bed> result = allocationEngine.allocateBed(admission);

        assertTrue(result.isPresent());
        assertEquals(freeBed.getId(), result.get().getId());
    }

    @Test
    @DisplayName("allocateBed should skip an ineligible candidate and take the next eligible one")
    void shouldSkipIneligibleCandidate() {
        Ward generalWard = new Ward(hospital, "General", WardType.GENERAL, GenderPolicy.UNISEX);
        assignId(generalWard, 16L);
        Ward icuWardLocal = new Ward(hospital, "ICU", WardType.ICU, GenderPolicy.UNISEX);
        assignId(icuWardLocal, 17L);
        Room generalRoom = new Room(generalWard, "G-12", false);
        assignId(generalRoom, 28L);
        Room icuRoom = new Room(icuWardLocal, "I-12", false);
        assignId(icuRoom, 29L);
        // Wrong ward type for a GENERAL request: will fail rule 3.
        Bed wrongWardBed = new Bed(icuRoom, "I-BED-12", BedType.STANDARD);
        assignId(wrongWardBed, 53L);
        Bed goodBed = new Bed(generalRoom, "G-BED-12", BedType.STANDARD);
        assignId(goodBed, 54L);
        Admission admission = new Admission("P-503", PatientGender.MALE, WardType.GENERAL,
                BedType.STANDARD, AdmissionPriority.NORMAL, false);

        when(bedRepository.findEligibleCandidateBedIds(
                WardType.GENERAL, BedType.STANDARD, false))
                .thenReturn(List.of(wrongWardBed.getId(), goodBed.getId()));
        when(bedRepository.findByIdWithPessimisticLock(wrongWardBed.getId()))
                .thenReturn(Optional.of(wrongWardBed));
        when(bedRepository.findByIdWithPessimisticLock(goodBed.getId()))
                .thenReturn(Optional.of(goodBed));
        // Rule 2 must pass for both so rule 3 is what rejects the first candidate.
        when(admissionRepository.findByAllocatedBedIdAndStatusIn(eq(wrongWardBed.getId()), anyList()))
                .thenReturn(Optional.empty());
        when(admissionRepository.findByAllocatedBedIdAndStatusIn(eq(goodBed.getId()), anyList()))
                .thenReturn(Optional.empty());
        when(bedRepository.findActiveBedsInRoom(generalRoom.getId()))
                .thenReturn(Collections.emptyList());

        Optional<Bed> result = allocationEngine.allocateBed(admission);

        assertTrue(result.isPresent());
        assertEquals(goodBed.getId(), result.get().getId());
    }

    @Test
    @DisplayName("allocateBed should skip a candidate that disappeared and return empty")
    void shouldSkipMissingCandidate() {
        Admission admission = new Admission("P-504", PatientGender.MALE, WardType.ICU,
                BedType.ICU, AdmissionPriority.NORMAL, false);

        when(bedRepository.findEligibleCandidateBedIds(
                WardType.ICU, BedType.ICU, false))
                .thenReturn(List.of(999L));
        when(bedRepository.findByIdWithPessimisticLock(999L))
                .thenReturn(Optional.empty());

        Optional<Bed> result = allocationEngine.allocateBed(admission);

        assertTrue(result.isEmpty());
    }
}
