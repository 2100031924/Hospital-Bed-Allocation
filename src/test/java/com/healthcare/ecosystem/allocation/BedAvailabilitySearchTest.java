package com.healthcare.ecosystem.allocation;

import com.healthcare.ecosystem.allocation.dto.response.BedResponse;
import com.healthcare.ecosystem.allocation.model.entity.Bed;
import com.healthcare.ecosystem.allocation.model.entity.Hospital;
import com.healthcare.ecosystem.allocation.model.entity.Room;
import com.healthcare.ecosystem.allocation.model.entity.Ward;
import com.healthcare.ecosystem.allocation.model.enums.*;
import com.healthcare.ecosystem.allocation.repository.*;
import com.healthcare.ecosystem.allocation.service.BedManagementService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Requirement: availability search by hospital, ward, bed type, patient gender, isolation.
 * Exercises the real JPQL, including the {@code :param IS NULL OR ...} optional-filter form.
 */
@SpringBootTest
@ActiveProfiles("test")
class BedAvailabilitySearchTest {

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

    private Long hospitalA;
    private Long hospitalB;
    private Long unisexGeneralWard;
    private Long femaleOnlyWard;
    private Long isolationWard;
    private Long isoRoom;
    private Long normalRoom;

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

        Hospital h1 = hospitalRepository.save(new Hospital("H-A", "Alpha", "Addr A"));
        Hospital h2 = hospitalRepository.save(new Hospital("H-B", "Beta", "Addr B"));
        hospitalA = h1.getId();
        hospitalB = h2.getId();

        Ward general = wardRepository.save(
                new Ward(h1, "General", WardType.GENERAL, GenderPolicy.UNISEX));
        Ward maternity = wardRepository.save(
                new Ward(h1, "Maternity", WardType.MATERNITY, GenderPolicy.FEMALE_ONLY));
        Ward icu = wardRepository.save(
                new Ward(h1, "ICU", WardType.ICU, GenderPolicy.UNISEX));
        unisexGeneralWard = general.getId();
        femaleOnlyWard = maternity.getId();
        isolationWard = icu.getId();

        normalRoom = roomRepository.save(new Room(general, "G-1", false)).getId();
        Long maternityRoom = roomRepository.save(new Room(maternity, "M-1", false)).getId();
        isoRoom = roomRepository.save(new Room(icu, "I-1", true)).getId();

        // 4 beds total: 1 UNISEX general, 1 FEMALE_ONLY maternity, 2 in the isolation room.
        bedRepository.save(new Bed(roomRepository.findById(normalRoom).orElseThrow(), "G-BED-1", BedType.STANDARD));
        bedRepository.save(new Bed(roomRepository.findById(maternityRoom).orElseThrow(), "M-BED-1", BedType.BARIATRIC));
        bedRepository.save(new Bed(roomRepository.findById(isoRoom).orElseThrow(), "I-BED-1", BedType.ICU));
        bedRepository.save(new Bed(roomRepository.findById(isoRoom).orElseThrow(), "I-BED-2", BedType.ICU));
    }

    @Test
    @DisplayName("No filters returns every available bed")
    void noFiltersReturnsAll() {
        List<BedResponse> all = bedManagementService.searchAvailableBeds(null, null, null, null, null, null);
        assertEquals(4, all.size());
    }

    @Test
    @DisplayName("Filter by ward type")
    void filterByWardType() {
        List<BedResponse> icuBeds =
                bedManagementService.searchAvailableBeds(null, null, null, null, WardType.ICU, null);
        assertEquals(2, icuBeds.size());
        assertTrue(icuBeds.stream().allMatch(b -> b.wardType() == WardType.ICU));
    }

    @Test
    @DisplayName("Filter by bed type")
    void filterByBedType() {
        List<BedResponse> standard =
                bedManagementService.searchAvailableBeds(null, null, BedType.STANDARD, null, null, null);
        assertEquals(1, standard.size(), "Only the general ward has a STANDARD bed");
        assertTrue(standard.stream().allMatch(b -> b.bedType() == BedType.STANDARD));

        List<BedResponse> icu =
                bedManagementService.searchAvailableBeds(null, null, BedType.ICU, null, null, null);
        assertEquals(2, icu.size());

        List<BedResponse> bariatric =
                bedManagementService.searchAvailableBeds(null, null, BedType.BARIATRIC, null, null, null);
        assertEquals(1, bariatric.size(), "The maternity bed is BARIATRIC");
    }

    @Test
    @DisplayName("Filter by hospital")
    void filterByHospital() {
        assertEquals(4, bedManagementService.searchAvailableBeds(hospitalA, null, null, null, null, null).size());
        assertEquals(0, bedManagementService.searchAvailableBeds(hospitalB, null, null, null, null, null).size());
    }

    @Test
    @DisplayName("Filter by isolation requirement")
    void filterByIsolation() {
        assertEquals(2, bedManagementService.searchAvailableBeds(null, null, null, true, null, null).size());
        assertEquals(2, bedManagementService.searchAvailableBeds(null, null, null, false, null, null).size());
    }

    @Test
    @DisplayName("Patient gender MALE excludes FEMALE_ONLY wards")
    void genderFilterExcludesFemaleOnlyWard() {
        List<BedResponse> maleBeds =
                bedManagementService.searchAvailableBeds(null, null, null, null, null, PatientGender.MALE);
        assertEquals(3, maleBeds.size(), "MALE must see UNISEX beds only (general 1 + ICU 2)");
        assertTrue(maleBeds.stream().noneMatch(b -> b.wardId().equals(femaleOnlyWard)),
                "MALE patient must not be offered a FEMALE_ONLY ward bed");
    }

    @Test
    @DisplayName("Patient gender FEMALE includes FEMALE_ONLY wards")
    void genderFilterIncludesFemaleOnlyWard() {
        List<BedResponse> femaleBeds =
                bedManagementService.searchAvailableBeds(null, null, null, null, null, PatientGender.FEMALE);
        assertEquals(4, femaleBeds.size(), "FEMALE sees UNISEX + FEMALE_ONLY");
        assertTrue(femaleBeds.stream().anyMatch(b -> b.wardId().equals(femaleOnlyWard)),
                "FEMALE patient must be offered the FEMALE_ONLY ward bed");
    }

    @Test
    @DisplayName("Gender OTHER is limited to UNISEX wards")
    void otherGenderOnlySeesUnisexWards() {
        List<BedResponse> other =
                bedManagementService.searchAvailableBeds(null, null, null, null, null, PatientGender.OTHER);
        assertEquals(3, other.size());
        assertTrue(other.stream().noneMatch(b -> b.wardId().equals(femaleOnlyWard)));
    }

    @Test
    @DisplayName("Combined filters: ward type + bed type + isolation + gender")
    void combinedFilters() {
        List<BedResponse> result = bedManagementService.searchAvailableBeds(
                hospitalA, isolationWard, BedType.ICU, true, WardType.ICU, PatientGender.MALE);
        assertEquals(2, result.size());
        assertTrue(result.stream().allMatch(BedResponse::isolationRoom));
        assertTrue(result.stream().allMatch(b -> b.hospitalId().equals(hospitalA)));
    }

    @Test
    @DisplayName("Contradictory filters return an empty list rather than erroring")
    void contradictoryFiltersReturnEmpty() {
        // General ward + ICU bed type: the general bed is STANDARD, so nothing matches
        assertEquals(0, bedManagementService.searchAvailableBeds(
                hospitalA, unisexGeneralWard, BedType.ICU, null, WardType.GENERAL, null).size());
        // Both ICU beds sit in the isolation room, so requiring non-isolation yields nothing
        assertEquals(0, bedManagementService.searchAvailableBeds(
                null, null, BedType.ICU, false, null, null).size());
        // FEMALE_ONLY maternity ward cannot serve a MALE patient
        assertEquals(0, bedManagementService.searchAvailableBeds(
                null, femaleOnlyWard, null, null, null, PatientGender.MALE).size());
    }

    @Test
    @DisplayName("Isolation + gender combined: a MALE patient needs an isolation room he is allowed in")
    void isolationAndGenderCombined() {
        // The ICU ward is UNISEX, so a MALE patient can use its isolation beds
        List<BedResponse> maleIso = bedManagementService.searchAvailableBeds(
                null, isolationWard, null, true, WardType.ICU, PatientGender.MALE);
        assertEquals(2, maleIso.size());
    }

    @Test
    @DisplayName("Non-AVAILABLE beds are never returned")
    void onlyAvailableBedsReturned() {
        Bed anyBed = bedRepository.findAll().get(0);
        anyBed.setStatus(BedStatus.MAINTENANCE);
        bedRepository.saveAndFlush(anyBed);

        List<BedResponse> all = bedManagementService.searchAvailableBeds(null, null, null, null, null, null);
        assertEquals(3, all.size(), "MAINTENANCE bed must be excluded");
        assertTrue(all.stream().noneMatch(b -> b.bedId().equals(anyBed.getId())));
    }
}