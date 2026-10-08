package com.healthcare.ecosystem.allocation.controller;

import com.healthcare.ecosystem.allocation.dto.request.CreateHospitalRequest;
import com.healthcare.ecosystem.allocation.dto.response.ApiResponse;
import com.healthcare.ecosystem.allocation.dto.response.HospitalResponse;
import com.healthcare.ecosystem.allocation.service.BedManagementService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/v1/hospitals")
@Tag(name = "Hospital Management", description = "Root of the inventory hierarchy")
public class HospitalController {

    private final BedManagementService bedManagementService;

    public HospitalController(BedManagementService bedManagementService) {
        this.bedManagementService = bedManagementService;
    }

    @PostMapping
    @Operation(summary = "Register a hospital")
    public ResponseEntity<ApiResponse<HospitalResponse>> createHospital(
            @Valid @RequestBody CreateHospitalRequest request) {
        HospitalResponse response = bedManagementService.createHospital(request);
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(ApiResponse.ok("Hospital created successfully", response));
    }

    @GetMapping
    @Operation(summary = "List all registered hospitals")
    public ResponseEntity<ApiResponse<List<HospitalResponse>>> getAllHospitals() {
        return ResponseEntity.ok(ApiResponse.ok(bedManagementService.getAllHospitals()));
    }

    @GetMapping("/{hospitalId}")
    @Operation(summary = "Get hospital details by ID")
    public ResponseEntity<ApiResponse<HospitalResponse>> getHospital(@PathVariable Long hospitalId) {
        return ResponseEntity.ok(ApiResponse.ok(bedManagementService.getHospital(hospitalId)));
    }
}