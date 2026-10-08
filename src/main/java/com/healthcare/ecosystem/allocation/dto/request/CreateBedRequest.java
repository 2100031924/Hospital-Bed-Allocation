package com.healthcare.ecosystem.allocation.dto.request;

import com.healthcare.ecosystem.allocation.model.enums.BedType;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

public record CreateBedRequest(
    @NotNull(message = "Room ID is required")
    Long roomId,

    @NotBlank(message = "Bed number is required")
    @Size(max = 50, message = "Bed number must not exceed 50 characters")
    String bedNumber,

    @NotNull(message = "Bed type is required")
    BedType bedType
) {}