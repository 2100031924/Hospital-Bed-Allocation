package com.healthcare.ecosystem.allocation.dto.request;

import com.healthcare.ecosystem.allocation.model.enums.AdmissionPriority;
import com.healthcare.ecosystem.allocation.model.enums.BedType;
import com.healthcare.ecosystem.allocation.model.enums.PatientGender;
import com.healthcare.ecosystem.allocation.model.enums.WardType;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

public record AdmissionCreationRequest(
    @NotBlank(message = "Patient ID is required")
    @Size(max = 100, message = "Patient ID must not exceed 100 characters")
    String patientId,

    @NotNull(message = "Patient gender is required")
    PatientGender patientGender,

    @NotNull(message = "Required ward type is mandatory")
    WardType requiredWardType,

    @NotNull(message = "Required bed type is mandatory")
    BedType requiredBedType,

    @NotNull(message = "Admission priority is mandatory")
    AdmissionPriority priority,

    boolean isolationRequired
) {}