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

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.*;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Proves the double-allocation race is closed: N concurrent admission requests for M physical
 * beds must yield exactly M reservations, and the rest must fall through to the waiting list
 * without a single bed being handed out twice.
 */
@SpringBootTest
@ActiveProfiles("test")
class BedAllocationConcurrencyTest {

    @Autowired
    private AdmissionService admissionService;

    @Autowired
    private HospitalRepository hospitalRepository;

    @Autowired
    private WardRepository wardRepository;

    @Autowired
    private RoomRepository roomRepository;

    @Autowired
    private BedRepository bedRepository;

    @Autowired
    private AdmissionRepository admissionRepository;

    @Autowired
    private WaitingListRepository waitingListRepository;

    @Autowired
    private BedStatusLogRepository bedStatusLogRepository;

    @BeforeEach
    void setUp() {
        // Children before parents (see AdmissionLifecycleIntegrationTest.setUp).
        // bed_status_logs must go before beds: every allocation writes an audit row.
        waitingListRepository.deleteAll();
        bedStatusLogRepository.deleteAll();
        admissionRepository.deleteAll();
        bedRepository.deleteAll();
        roomRepository.deleteAll();
        wardRepository.deleteAll();
        hospitalRepository.deleteAll();

        Hospital hospital = hospitalRepository.save(new Hospital("H1", "Apollo General", "Zone 1"));
        Ward ward = wardRepository.save(
                new Ward(hospital, "Critical ICU", WardType.ICU, GenderPolicy.UNISEX));
        Room room = roomRepository.save(new Room(ward, "ICU-100", false));
        bedRepository.save(new Bed(room, "ICU-BED-SINGLE", BedType.ICU));
    }

    @Test
    @DisplayName("Race Condition Test: 10 concurrent requests for 1 available bed. "
            + "Exactly 1 must reserve, 9 must enqueue to Waiting List.")
    void testConcurrentBedAllocationRaceCondition() throws InterruptedException {
        int threadCount = 10;
        ExecutorService executorService = Executors.newFixedThreadPool(threadCount);
        CountDownLatch startLatch = new CountDownLatch(1);
        CountDownLatch finishLatch = new CountDownLatch(threadCount);

        List<AdmissionResponse> responses = Collections.synchronizedList(new ArrayList<>());

        for (int i = 0; i < threadCount; i++) {
            final String patientId = "PATIENT-CONCURRENT-" + i;
            executorService.submit(() -> {
                try {
                    startLatch.await(); // guarantee all threads fire at the same instant
                    AdmissionCreationRequest request = new AdmissionCreationRequest(
                            patientId,
                            PatientGender.MALE,
                            WardType.ICU,
                            BedType.ICU,
                            AdmissionPriority.URGENT,
                            false
                    );
                    responses.add(admissionService.createAdmissionRequest(request));
                } catch (Exception e) {
                    e.printStackTrace();
                } finally {
                    finishLatch.countDown();
                }
            });
        }

        startLatch.countDown();
        assertEquals(true, finishLatch.await(60, TimeUnit.SECONDS), "All threads must finish in time");
        executorService.shutdown();
        assertEquals(true, executorService.awaitTermination(30, TimeUnit.SECONDS), "Pool must drain");

        assertEquals(threadCount, responses.size(), "All concurrent requests must complete");

        long reservedCount = responses.stream()
                .filter(r -> r.status() == AdmissionStatus.RESERVED)
                .count();
        long waitingCount = responses.stream()
                .filter(r -> r.status() == AdmissionStatus.WAITING_LIST)
                .count();

        assertEquals(1, reservedCount, "Exactly 1 admission should acquire the RESERVED bed");
        assertEquals(9, waitingCount, "The other 9 admissions must be queued without collision");

        List<Bed> beds = bedRepository.findAll();
        assertEquals(1, beds.size());
        assertEquals(BedStatus.RESERVED, beds.get(0).getStatus(), "The single physical bed must be RESERVED");

        // No bed may be referenced by more than one live admission.
        long reservedAdmissions = admissionRepository.findByStatusOrderByIdAsc(AdmissionStatus.RESERVED).size();
        assertEquals(1, reservedAdmissions, "Only one admission may hold the bed");
        assertEquals(9, waitingListRepository.count(), "Nine entries must sit on the waiting list");
    }
}