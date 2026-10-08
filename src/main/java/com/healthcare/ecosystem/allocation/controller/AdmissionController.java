package com.healthcare.ecosystem.allocation.controller;

import com.healthcare.ecosystem.allocation.dto.request.AdmissionCreationRequest;
import com.healthcare.ecosystem.allocation.dto.response.AdmissionResponse;
import com.healthcare.ecosystem.allocation.dto.response.ApiResponse;
import com.healthcare.ecosystem.allocation.service.AdmissionService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/v1/admissions")
@Tag(name = "Admission & Allocation Engine",
        description = "Admission orchestration, reservation, confirmation, and discharge")
public class AdmissionController {

    private final AdmissionService admissionService;

    public AdmissionController(AdmissionService admissionService) {
        this.admissionService = admissionService;
    }

    @PostMapping("/request")
    @Operation(summary = "Request patient admission and execute intelligent bed allocation")
    public ResponseEntity<ApiResponse<AdmissionResponse>> requestAdmission(
            @Valid @RequestBody AdmissionCreationRequest request) {
        AdmissionResponse response = admissionService.createAdmissionRequest(request);
        return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.ok("Admission processed", response));
    }

    @GetMapping("/{admissionId}")
    @Operation(summary = "Get admission details by ID")
    public ResponseEntity<ApiResponse<AdmissionResponse>> getAdmissionById(@PathVariable Long admissionId) {
        AdmissionResponse response = admissionService.getAdmissionById(admissionId);
        return ResponseEntity.ok(ApiResponse.ok(response));
    }

    @GetMapping("/patient/{patientId}")
    @Operation(summary = "List active admissions for a patient")
    public ResponseEntity<ApiResponse<List<AdmissionResponse>>> getByPatient(@PathVariable String patientId) {
        List<AdmissionResponse> response = admissionService.getActiveAdmissionsForPatient(patientId);
        return ResponseEntity.ok(ApiResponse.ok(response));
    }

    @PostMapping("/{admissionId}/reserve")
    @Operation(summary = "Explicitly reserve a bed for a waiting/pending admission")
    public ResponseEntity<ApiResponse<AdmissionResponse>> reserveBed(
            @PathVariable Long admissionId,
            @RequestParam Long bedId) {
        AdmissionResponse response = admissionService.reserveBedManually(admissionId, bedId);
        return ResponseEntity.ok(ApiResponse.ok("Bed reserved successfully", response));
    }

    @PostMapping("/{admissionId}/confirm")
    @Operation(summary = "Confirm patient check-in / arrival (RESERVED -> OCCUPIED)")
    public ResponseEntity<ApiResponse<AdmissionResponse>> confirmAdmission(@PathVariable Long admissionId) {
        AdmissionResponse response = admissionService.confirmAdmission(admissionId);
        return ResponseEntity.ok(ApiResponse.ok("Admission confirmed. Bed is now OCCUPIED", response));
    }

    @PostMapping("/{admissionId}/discharge")
    @Operation(summary = "Discharge patient, closing admission and moving bed to MAINTENANCE")
    public ResponseEntity<ApiResponse<AdmissionResponse>> dischargeAdmission(@PathVariable Long admissionId) {
        AdmissionResponse response = admissionService.dischargeAdmission(admissionId);
        return ResponseEntity.ok(ApiResponse.ok("Patient discharged. Bed queued for MAINTENANCE", response));
    }

    @PostMapping("/{admissionId}/cancel")
    @Operation(summary = "Cancel an admission and release its bed back to the waiting list")
    public ResponseEntity<ApiResponse<AdmissionResponse>> cancelAdmission(
            @PathVariable Long admissionId,
            @RequestParam(required = false, defaultValue = "Cancelled by request") String reason) {
        AdmissionResponse response = admissionService.cancelAdmission(admissionId, reason);
        return ResponseEntity.ok(ApiResponse.ok("Admission cancelled and bed released", response));
    }
}