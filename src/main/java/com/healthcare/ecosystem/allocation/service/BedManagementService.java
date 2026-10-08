package com.healthcare.ecosystem.allocation.service;

import com.healthcare.ecosystem.allocation.dto.request.CreateBedRequest;
import com.healthcare.ecosystem.allocation.dto.request.CreateHospitalRequest;
import com.healthcare.ecosystem.allocation.dto.request.CreateRoomRequest;
import com.healthcare.ecosystem.allocation.dto.request.CreateWardRequest;
import com.healthcare.ecosystem.allocation.dto.response.BedResponse;
import com.healthcare.ecosystem.allocation.dto.response.HospitalResponse;
import com.healthcare.ecosystem.allocation.dto.response.RoomResponse;
import com.healthcare.ecosystem.allocation.dto.response.WardResponse;
import com.healthcare.ecosystem.allocation.model.enums.BedStatus;
import com.healthcare.ecosystem.allocation.model.enums.BedType;
import com.healthcare.ecosystem.allocation.model.enums.PatientGender;
import com.healthcare.ecosystem.allocation.model.enums.WardType;

import java.util.List;

public interface BedManagementService {

    // ---------- Hospital ----------

    HospitalResponse createHospital(CreateHospitalRequest request);

    HospitalResponse getHospital(Long hospitalId);

    List<HospitalResponse> getAllHospitals();

    // ---------- Ward ----------

    WardResponse createWard(CreateWardRequest request);

    WardResponse getWard(Long wardId);

    List<BedResponse> getBedsByWard(Long wardId);

    // ---------- Room ----------

    RoomResponse createRoom(CreateRoomRequest request);

    RoomResponse getRoom(Long roomId);

    List<RoomResponse> getRoomsByWard(Long wardId);

    // ---------- Bed ----------

    BedResponse createBed(CreateBedRequest request);

    BedResponse getBedById(Long bedId);

    /**
     * Availability search. All filters are optional (null = do not filter).
     *
     * @param patientGender when supplied, only beds whose ward gender policy admits that
     *                     patient gender are returned
     * @param wardType      when supplied, restricts to a ward type
     */
    List<BedResponse> searchAvailableBeds(Long hospitalId, Long wardId, BedType bedType,
                                          Boolean isolationRequired, WardType wardType,
                                          PatientGender patientGender);

    BedResponse blockBed(Long bedId, String reason);

    BedResponse completeMaintenance(Long bedId);

    long countBedsByStatus(BedStatus status);
}