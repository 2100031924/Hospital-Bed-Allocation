package com.healthcare.ecosystem.allocation.service.impl;

import com.healthcare.ecosystem.allocation.dto.request.CreateBedRequest;
import com.healthcare.ecosystem.allocation.dto.request.CreateHospitalRequest;
import com.healthcare.ecosystem.allocation.dto.request.CreateRoomRequest;
import com.healthcare.ecosystem.allocation.dto.request.CreateWardRequest;
import com.healthcare.ecosystem.allocation.dto.response.BedResponse;
import com.healthcare.ecosystem.allocation.dto.response.HospitalResponse;
import com.healthcare.ecosystem.allocation.dto.response.RoomResponse;
import com.healthcare.ecosystem.allocation.dto.response.WardResponse;
import com.healthcare.ecosystem.allocation.exception.BedAllocationConflictException;
import com.healthcare.ecosystem.allocation.exception.InvalidStateTransitionException;
import com.healthcare.ecosystem.allocation.exception.ResourceNotFoundException;
import com.healthcare.ecosystem.allocation.model.entity.Bed;
import com.healthcare.ecosystem.allocation.model.entity.BedStatusLog;
import com.healthcare.ecosystem.allocation.model.entity.Hospital;
import com.healthcare.ecosystem.allocation.model.entity.Room;
import com.healthcare.ecosystem.allocation.model.entity.Ward;
import com.healthcare.ecosystem.allocation.model.enums.BedStatus;
import com.healthcare.ecosystem.allocation.model.enums.BedType;
import com.healthcare.ecosystem.allocation.model.enums.GenderPolicy;
import com.healthcare.ecosystem.allocation.model.enums.PatientGender;
import com.healthcare.ecosystem.allocation.model.enums.WardType;
import com.healthcare.ecosystem.allocation.repository.BedRepository;
import com.healthcare.ecosystem.allocation.repository.BedStatusLogRepository;
import com.healthcare.ecosystem.allocation.repository.HospitalRepository;
import com.healthcare.ecosystem.allocation.repository.RoomRepository;
import com.healthcare.ecosystem.allocation.repository.WardRepository;
import com.healthcare.ecosystem.allocation.service.BedManagementService;
import com.healthcare.ecosystem.allocation.service.WaitingListService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.ConcurrencyFailureException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import jakarta.persistence.LockTimeoutException;
import jakarta.persistence.PessimisticLockException;

import java.util.List;
import java.util.Locale;

@Service
public class BedManagementServiceImpl implements BedManagementService {

    private static final Logger log = LoggerFactory.getLogger(BedManagementServiceImpl.class);

    private final WardRepository wardRepository;
    private final BedRepository bedRepository;
    private final HospitalRepository hospitalRepository;
    private final RoomRepository roomRepository;
    private final BedStatusLogRepository bedStatusLogRepository;
    private final WaitingListService waitingListService;

    public BedManagementServiceImpl(WardRepository wardRepository,
                                    BedRepository bedRepository,
                                    HospitalRepository hospitalRepository,
                                    RoomRepository roomRepository,
                                    BedStatusLogRepository bedStatusLogRepository,
                                    WaitingListService waitingListService) {
        this.wardRepository = wardRepository;
        this.bedRepository = bedRepository;
        this.hospitalRepository = hospitalRepository;
        this.roomRepository = roomRepository;
        this.bedStatusLogRepository = bedStatusLogRepository;
        this.waitingListService = waitingListService;
    }

    // ================= Hospital =================

    @Override
    @Transactional
    public HospitalResponse createHospital(CreateHospitalRequest request) {
        String code = request.code().trim().toUpperCase(Locale.ROOT);
        if (hospitalRepository.findByCode(code).isPresent()) {
            throw new InvalidStateTransitionException("Hospital code already exists: " + code);
        }
        Hospital hospital = hospitalRepository.save(
                new Hospital(code, request.name().trim(), request.address().trim()));
        return mapHospitalToResponse(hospital);
    }

    @Override
    @Transactional(readOnly = true)
    public HospitalResponse getHospital(Long hospitalId) {
        return mapHospitalToResponse(findHospital(hospitalId));
    }

    @Override
    @Transactional(readOnly = true)
    public List<HospitalResponse> getAllHospitals() {
        return hospitalRepository.findAll().stream()
                .map(this::mapHospitalToResponse)
                .toList();
    }

    // ================= Room =================

    @Override
    @Transactional
    public RoomResponse createRoom(CreateRoomRequest request) {
        Ward ward = findWard(request.wardId());
        String roomNumber = request.roomNumber().trim();
        if (roomRepository.findByWardIdAndRoomNumber(ward.getId(), roomNumber).isPresent()) {
            throw new InvalidStateTransitionException(
                    "Room " + roomNumber + " already exists in ward " + ward.getId());
        }
        Room room = roomRepository.save(new Room(ward, roomNumber, request.isolationRoom()));
        return mapRoomToResponse(room);
    }

    @Override
    @Transactional(readOnly = true)
    public RoomResponse getRoom(Long roomId) {
        return mapRoomToResponse(findRoom(roomId));
    }

    @Override
    @Transactional(readOnly = true)
    public List<RoomResponse> getRoomsByWard(Long wardId) {
        return roomRepository.findByWardId(wardId).stream()
                .map(this::mapRoomToResponse)
                .toList();
    }

    @Override
    @Transactional
    public WardResponse createWard(CreateWardRequest request) {
        Hospital hospital = hospitalRepository.findById(request.hospitalId())
                .orElseThrow(() -> new ResourceNotFoundException(
                        "Hospital not found: " + request.hospitalId()));
        Ward ward = new Ward(hospital, request.name(), request.wardType(), request.genderPolicy());
        ward = wardRepository.save(ward);
        return mapWardToResponse(ward);
    }

    @Override
    @Transactional(readOnly = true)
    public WardResponse getWard(Long wardId) {
        return mapWardToResponse(findWard(wardId));
    }

    @Override
    @Transactional(readOnly = true)
    public List<BedResponse> getBedsByWard(Long wardId) {
        return bedRepository.findByRoomWardId(wardId).stream()
                .map(this::mapToResponse)
                .toList();
    }

    @Override
    @Transactional
    public BedResponse createBed(CreateBedRequest request) {
        Room room = roomRepository.findById(request.roomId())
                .orElseThrow(() -> new ResourceNotFoundException("Room not found: " + request.roomId()));

        Bed bed = new Bed(room, request.bedNumber(), request.bedType());
        bed = bedRepository.save(bed);

        bedStatusLogRepository.save(new BedStatusLog(
                bed, BedStatus.AVAILABLE, BedStatus.AVAILABLE, "INITIAL_PROVISIONING", "Bed created"
        ));

        return mapToResponse(bed);
    }

    @Override
    @Transactional(readOnly = true)
    public BedResponse getBedById(Long bedId) {
        return mapToResponse(findBed(bedId));
    }

    @Override
    @Transactional(readOnly = true)
    public List<BedResponse> searchAvailableBeds(Long hospitalId, Long wardId,
                                                 BedType bedType, Boolean isolationRequired,
                                                 WardType wardType, PatientGender patientGender) {
        return bedRepository.searchAvailableBeds(hospitalId, wardId, bedType, isolationRequired,
                        wardType, allowedPoliciesFor(patientGender)).stream()
                .map(this::mapToResponse)
                .toList();
    }

    /**
     * Ward gender policies that admit the given patient gender. A null gender means
     * "do not filter", so every policy is admissible.
     */
    static List<GenderPolicy> allowedPoliciesFor(PatientGender patientGender) {
        if (patientGender == null) {
            return List.of(GenderPolicy.values());
        }
        return switch (patientGender) {
            case MALE -> List.of(GenderPolicy.UNISEX, GenderPolicy.MALE_ONLY);
            case FEMALE -> List.of(GenderPolicy.UNISEX, GenderPolicy.FEMALE_ONLY);
            case OTHER -> List.of(GenderPolicy.UNISEX);
        };
    }

    /** Administrative hold: takes a bed out of circulation (e.g. repair, deep clean, bed bug). */
    @Override
    @Transactional
    public BedResponse blockBed(Long bedId, String reason) {
        Bed bed = lockBed(bedId);

        if (bed.getStatus() == BedStatus.OCCUPIED) {
            throw new InvalidStateTransitionException(
                    "Cannot block an OCCUPIED bed. Discharge the admission first.");
        }

        BedStatus previousStatus = bed.getStatus();
        bed.setStatus(BedStatus.BLOCKED);
        bedRepository.save(bed);

        bedStatusLogRepository.save(new BedStatusLog(
                bed, previousStatus, BedStatus.BLOCKED, "BED_ADMIN", reason
        ));
        log.info("Bed {} blocked: {}", bedId, reason);

        return mapToResponse(bed);
    }


    @Override
    @Transactional
    public BedResponse completeMaintenance(Long bedId) {
        Bed bed = lockBed(bedId);

        if (bed.getStatus() != BedStatus.MAINTENANCE) {
            throw new InvalidStateTransitionException(
                    "Bed is not under MAINTENANCE. Current status: " + bed.getStatus());
        }

        bed.setStatus(BedStatus.AVAILABLE);
        bedRepository.save(bed);

        bedStatusLogRepository.save(new BedStatusLog(
                bed, BedStatus.MAINTENANCE, BedStatus.AVAILABLE,
                "MAINTENANCE_SUPERVISOR", "Sanitization complete"
        ));

        // The row lock held by this transaction protects the read-modify-write below.
        waitingListService.evaluateWaitingListForBed(bed);

        return mapToResponse(bed);
    }

    @Override
    @Transactional(readOnly = true)
    public long countBedsByStatus(BedStatus status) {
        return bedRepository.countByStatus(status);
    }

    private Hospital findHospital(Long hospitalId) {
        return hospitalRepository.findById(hospitalId)
                .orElseThrow(() -> new ResourceNotFoundException("Hospital not found: " + hospitalId));
    }

    private Ward findWard(Long wardId) {
        return wardRepository.findById(wardId)
                .orElseThrow(() -> new ResourceNotFoundException("Ward not found: " + wardId));
    }

    private Room findRoom(Long roomId) {
        return roomRepository.findById(roomId)
                .orElseThrow(() -> new ResourceNotFoundException("Room not found: " + roomId));
    }

    private HospitalResponse mapHospitalToResponse(Hospital hospital) {
        return new HospitalResponse(
                hospital.getId(),
                hospital.getCode(),
                hospital.getName(),
                hospital.getAddress());
    }

    private RoomResponse mapRoomToResponse(Room room) {
        Ward ward = room.getWard();

        Long wardId = null;
        String wardName = null;
        WardType wardType = null;
        if (ward != null) {
            wardId = ward.getId();
            wardName = ward.getName();
            wardType = ward.getWardType();
        }

        int bedCount = 0;
        if (room.getBeds() != null) {
            bedCount = room.getBeds().size();
        }

        return new RoomResponse(
                room.getId(),
                wardId,
                wardName,
                wardType,
                room.getRoomNumber(),
                room.isIsolationRoom(),
                (long) bedCount);
    }

    private Bed findBed(Long bedId) {
        return bedRepository.findById(bedId)
                .orElseThrow(() -> new ResourceNotFoundException("Bed not found: " + bedId));
    }

    private Bed lockBed(Long bedId) {
        try {
            return bedRepository.findByIdWithPessimisticLock(bedId)
                    .orElseThrow(() -> new ResourceNotFoundException("Bed not found: " + bedId));
        } catch (ConcurrencyFailureException | PessimisticLockException | LockTimeoutException ex) {
            throw BedAllocationConflictException.from(ex);
        }
    }

    private WardResponse mapWardToResponse(Ward ward) {
        Long hospitalId = null;
        if (ward.getHospital() != null) {
            hospitalId = ward.getHospital().getId();
        }
        return new WardResponse(
                ward.getId(),
                hospitalId,
                ward.getName(),
                ward.getWardType(),
                ward.getGenderPolicy()
        );
    }

    private BedResponse mapToResponse(Bed bed) {
        Room room = bed.getRoom();

        Long roomId = null;
        String roomNumber = null;
        boolean isolationRoom = false;
        if (room != null) {
            roomId = room.getId();
            roomNumber = room.getRoomNumber();
            isolationRoom = room.isIsolationRoom();
        }

        Ward ward = null;
        if (room != null) {
            ward = room.getWard();
        }

        Long wardId = null;
        String wardName = null;
        WardType wardType = null;
        Long hospitalId = null;
        if (ward != null) {
            wardId = ward.getId();
            wardName = ward.getName();
            wardType = ward.getWardType();
            if (ward.getHospital() != null) {
                hospitalId = ward.getHospital().getId();
            }
        }

        return new BedResponse(
                bed.getId(),
                bed.getBedNumber(),
                bed.getBedType(),
                bed.getStatus(),
                roomId,
                roomNumber,
                isolationRoom,
                wardId,
                wardName,
                wardType,
                hospitalId
        );
    }
}