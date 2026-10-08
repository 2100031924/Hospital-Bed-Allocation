package com.healthcare.ecosystem.allocation.dto.request;

import com.healthcare.ecosystem.allocation.model.enums.GenderPolicy;
import com.healthcare.ecosystem.allocation.model.enums.WardType;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

public record CreateWardRequest(
    @NotNull(message = "Hospital ID is required")
    Long hospitalId,

    @NotBlank(message = "Ward name is required")
    @Size(max = 100, message = "Ward name must not exceed 100 characters")
    String name,

    @NotNull(message = "Ward type is required")
    WardType wardType,

    @NotNull(message = "Gender policy is required")
    GenderPolicy genderPolicy
) {}