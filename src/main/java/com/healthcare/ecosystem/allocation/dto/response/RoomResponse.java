package com.healthcare.ecosystem.allocation.dto.response;

import com.healthcare.ecosystem.allocation.model.enums.WardType;

public record RoomResponse(
    Long id,
    Long wardId,
    String wardName,
    WardType wardType,
    String roomNumber,
    boolean isolationRoom,
    Long bedCount
) {}