package com.healthcare.ecosystem.allocation;

import com.healthcare.ecosystem.allocation.dto.request.*;
import com.healthcare.ecosystem.allocation.dto.response.*;
import com.healthcare.ecosystem.allocation.model.entity.Hospital;
import com.healthcare.ecosystem.allocation.model.enums.GenderPolicy;
import com.healthcare.ecosystem.allocation.model.enums.WardType;
import com.healthcare.ecosystem.allocation.repository.*;
import com.healthcare.ecosystem.allocation.service.BedManagementService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * Requirement 1: management for Hospital, Ward, Room, Bed, Admission.
 * Hospital and Room previously had no API surface at all; these tests pin the new endpoints,
 * including their validation and duplicate-detection behaviour.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class HospitalRoomManagementTest {

    @Autowired
    private BedManagementService bedManagementService;

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private BedRepository bedRepository;
    @Autowired
    private RoomRepository roomRepository;
    @Autowired
    private WardRepository wardRepository;
    @Autowired
    private HospitalRepository hospitalRepository;
    @Autowired
    private AdmissionRepository admissionRepository;
    @Autowired
    private WaitingListRepository waitingListRepository;
    @Autowired
    private BedStatusLogRepository bedStatusLogRepository;

    @BeforeEach
    void setUp() {
        // Children before parents (see AdmissionLifecycleIntegrationTest.setUp).
        // bed_status_logs must go before beds: createBed writes an audit row.
        waitingListRepository.deleteAll();
        bedStatusLogRepository.deleteAll();
        admissionRepository.deleteAll();
        bedRepository.deleteAll();
        roomRepository.deleteAll();
        wardRepository.deleteAll();
        hospitalRepository.deleteAll();
    }

    @Test
    @DisplayName("Create hospital, ward, room and bed through the service layer")
    void createFullHierarchy() {
        HospitalResponse hospital = bedManagementService.createHospital(
                new CreateHospitalRequest("hosp-01", "  City General  ", "  1 Main St  "));
        assertNotNull(hospital.id());
        assertEquals("HOSP-01", hospital.code(), "Code should be normalised to uppercase");
        assertEquals("City General", hospital.name(), "Name should be trimmed");
        assertEquals("1 Main St", hospital.address());

        WardResponse ward = bedManagementService.createWard(new CreateWardRequest(
                hospital.id(), "ICU", WardType.ICU, GenderPolicy.UNISEX));
        assertEquals(hospital.id(), ward.hospitalId());

        RoomResponse room = bedManagementService.createRoom(
                new CreateRoomRequest(ward.id(), "ICU-1", true));
        assertEquals(ward.id(), room.wardId());
        assertTrue(room.isolationRoom());
        // bedCount is a boxed Long: compare unboxed to avoid the ambiguous
        // assertEquals(long,Long) vs assertEquals(Object,Object) overload resolution.
        assertEquals(0L, room.bedCount().longValue());

        BedResponse bed = bedManagementService.createBed(
                new CreateBedRequest(room.id(), "B-1", com.healthcare.ecosystem.allocation.model.enums.BedType.ICU));
        assertEquals(room.id(), bed.roomId());
        assertTrue(bed.isolationRoom());

        RoomResponse reloaded = bedManagementService.getRoom(room.id());
        assertEquals(1L, reloaded.bedCount().longValue(), "bedCount must reflect the created bed");
    }

    @Test
    @DisplayName("Duplicate hospital code is rejected")
    void duplicateHospitalCodeRejected() {
        bedManagementService.createHospital(new CreateHospitalRequest("DUP-1", "First", "Addr"));
        assertThrows(RuntimeException.class, () -> bedManagementService.createHospital(
                new CreateHospitalRequest("dup-1", "Second", "Addr")),
                "Codes are case-normalised, so dup-1 collides with DUP-1");
        assertEquals(1, hospitalRepository.count());
    }

    @Test
    @DisplayName("Duplicate room number within the same ward is rejected, but allowed across wards")
    void duplicateRoomNumberScopedToWard() {
        HospitalResponse h = bedManagementService.createHospital(
                new CreateHospitalRequest("H-R", "R", "A"));
        WardResponse w1 = bedManagementService.createWard(
                new CreateWardRequest(h.id(), "W1", WardType.GENERAL, GenderPolicy.UNISEX));
        WardResponse w2 = bedManagementService.createWard(
                new CreateWardRequest(h.id(), "W2", WardType.GENERAL, GenderPolicy.UNISEX));

        bedManagementService.createRoom(new CreateRoomRequest(w1.id(), "R-1", false));
        assertThrows(RuntimeException.class,
                () -> bedManagementService.createRoom(new CreateRoomRequest(w1.id(), "R-1", false)));

        RoomResponse ok = bedManagementService.createRoom(new CreateRoomRequest(w2.id(), "R-1", false));
        assertNotNull(ok.id(), "Same room number in a different ward must be allowed");
    }

    @Test
    @DisplayName("Unknown ward / room / hospital produce not-found errors")
    void unknownReferencesThrow() {
        assertThrows(RuntimeException.class,
                () -> bedManagementService.createRoom(new CreateRoomRequest(9999L, "X", false)));
        assertThrows(RuntimeException.class, () -> bedManagementService.getRoom(9999L));
        assertThrows(RuntimeException.class, () -> bedManagementService.getHospital(9999L));
    }

    @Test
    @DisplayName("POST /api/v1/hospitals returns 201")
    void createHospitalEndpoint() throws Exception {
        mockMvc.perform(post("/api/v1/hospitals")
                        .contentType("application/json")
                        .content("""
                                {"code":"POST-1","name":"Posted Hospital","address":"9 API Road"}
                                """))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.code").value("POST-1"));
    }

    @Test
    @DisplayName("POST /api/v1/hospitals rejects a blank code with 400")
    void createHospitalValidation() throws Exception {
        mockMvc.perform(post("/api/v1/hospitals")
                        .contentType("application/json")
                        .content("""
                                {"code":"","name":"X","address":"Y"}
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.success").value(false));
    }

    @Test
    @DisplayName("POST /api/v1/rooms returns 201 and GET returns it back")
    void roomEndpoints() throws Exception {
        HospitalResponse h = bedManagementService.createHospital(
                new CreateHospitalRequest("H-ROOM", "Room Host", "A"));
        WardResponse w = bedManagementService.createWard(
                new CreateWardRequest(h.id(), "W", WardType.GENERAL, GenderPolicy.UNISEX));

        String body = mockMvc.perform(post("/api/v1/rooms")
                        .contentType("application/json")
                        .content("""
                                {"wardId":%d,"roomNumber":"ISO-9","isolationRoom":true}
                                """.formatted(w.id())))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.isolationRoom").value(true))
                .andReturn().getResponse().getContentAsString();

        // JsonPath returns Integer for small JSON numbers; go through Number so this
        // does not throw ClassCastException when unboxing to long.
        long roomId = ((Number) com.jayway.jsonpath.JsonPath.read(body, "$.data.id")).longValue();

        mockMvc.perform(get("/api/v1/rooms/" + roomId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.roomNumber").value("ISO-9"));

        mockMvc.perform(get("/api/v1/rooms/ward/" + w.id()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(1));
    }

    @Test
    @DisplayName("POST /api/v1/hospitals/{id}/wards still works for a hospital created via API")
    void wardCreationAgainstApiCreatedHospital() throws Exception {
        mockMvc.perform(post("/api/v1/hospitals")
                        .contentType("application/json")
                        .content("""
                                {"code":"H-CHAIN","name":"Chain","address":"A"}
                                """))
                .andExpect(status().isCreated());

        Hospital saved = hospitalRepository.findByCode("H-CHAIN").orElseThrow();

        mockMvc.perform(post("/api/v1/wards")
                        .contentType("application/json")
                        .content("""
                                {"hospitalId":%d,"name":"Surgical","wardType":"SURGICAL","genderPolicy":"MALE_ONLY"}
                                """.formatted(saved.getId())))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.genderPolicy").value("MALE_ONLY"));
    }

    @Test
    @DisplayName("GET /api/v1/hospitals lists registered hospitals")
    void listHospitals() throws Exception {
        bedManagementService.createHospital(new CreateHospitalRequest("L-1", "One", "A"));
        bedManagementService.createHospital(new CreateHospitalRequest("L-2", "Two", "B"));

        mockMvc.perform(get("/api/v1/hospitals"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(2));
    }

    @Test
    @DisplayName("GET /api/v1/beds/available accepts patientGender and wardType")
    void availabilityEndpointAcceptsNewFilters() throws Exception {
        HospitalResponse h = bedManagementService.createHospital(
                new CreateHospitalRequest("H-SRCH", "Search", "A"));
        WardResponse maternity = bedManagementService.createWard(new CreateWardRequest(
                h.id(), "Maternity", WardType.MATERNITY, GenderPolicy.FEMALE_ONLY));
        RoomResponse room = bedManagementService.createRoom(
                new CreateRoomRequest(maternity.id(), "M-1", false));
        bedManagementService.createBed(new CreateBedRequest(room.id(), "M-BED", com.healthcare.ecosystem.allocation.model.enums.BedType.STANDARD));

        mockMvc.perform(get("/api/v1/beds/available")
                        .param("patientGender", "FEMALE"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(1));

        mockMvc.perform(get("/api/v1/beds/available")
                        .param("patientGender", "MALE"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(0));
    }
}