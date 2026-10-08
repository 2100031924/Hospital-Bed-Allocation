package com.healthcare.ecosystem.allocation.dto.response;

import com.healthcare.ecosystem.allocation.model.enums.BedStatus;
import com.healthcare.ecosystem.allocation.model.enums.BedType;
import com.healthcare.ecosystem.allocation.model.enums.WardType;

public record BedResponse(
    Long bedId,
    String bedNumber,
    BedType bedType,
    BedStatus status,
    Long roomId,
    String roomNumber,
    boolean isolationRoom,
    Long wardId,
    String wardName,
    WardType wardType,
    Long hospitalId
) {}