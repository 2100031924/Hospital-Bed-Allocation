package com.healthcare.ecosystem.allocation.dto.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

public record CreateHospitalRequest(
    @NotBlank(message = "Hospital code is required")
    @Size(max = 50, message = "Hospital code must not exceed 50 characters")
    @Pattern(regexp = "^[A-Za-z0-9_-]+$",
            message = "Hospital code may contain only letters, digits, hyphen and underscore")
    String code,

    @NotBlank(message = "Hospital name is required")
    @Size(max = 255, message = "Hospital name must not exceed 255 characters")
    String name,

    @NotBlank(message = "Address is required")
    @Size(max = 500, message = "Address must not exceed 500 characters")
    String address
) {}