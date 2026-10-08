package com.healthcare.ecosystem.allocation.service;

import com.healthcare.ecosystem.allocation.dto.request.AdmissionCreationRequest;
import com.healthcare.ecosystem.allocation.dto.response.AdmissionResponse;

import java.util.List;

public interface AdmissionService {
    AdmissionResponse createAdmissionRequest(AdmissionCreationRequest request);

    AdmissionResponse reserveBedManually(Long admissionId, Long bedId);

    AdmissionResponse confirmAdmission(Long admissionId);

    AdmissionResponse dischargeAdmission(Long admissionId);

    AdmissionResponse cancelAdmission(Long admissionId, String reason);

    AdmissionResponse getAdmissionById(Long admissionId);

    List<AdmissionResponse> getActiveAdmissionsForPatient(String patientId);
}