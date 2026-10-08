package com.healthcare.ecosystem.allocation.dto.response;

import com.healthcare.ecosystem.allocation.model.enums.AdmissionPriority;
import com.healthcare.ecosystem.allocation.model.enums.AdmissionStatus;
import com.healthcare.ecosystem.allocation.model.enums.BedType;
import com.healthcare.ecosystem.allocation.model.enums.PatientGender;
import com.healthcare.ecosystem.allocation.model.enums.WardType;

import java.time.Instant;

public record AdmissionResponse(
    Long admissionId,
    String patientId,
    PatientGender patientGender,
    WardType requiredWardType,
    BedType requiredBedType,
    AdmissionPriority priority,
    boolean isolationRequired,
    AdmissionStatus status,
    Long allocatedBedId,
    Instant reservationExpiresAt,
    Instant admissionTime,
    Instant dischargeTime,
    String cancellationReason
) {}