package com.healthcare.ecosystem.allocation.controller;

import com.healthcare.ecosystem.allocation.dto.request.CreateBedRequest;
import com.healthcare.ecosystem.allocation.dto.response.ApiResponse;
import com.healthcare.ecosystem.allocation.dto.response.BedResponse;
import com.healthcare.ecosystem.allocation.model.enums.BedStatus;
import com.healthcare.ecosystem.allocation.model.enums.BedType;
import com.healthcare.ecosystem.allocation.model.enums.PatientGender;
import com.healthcare.ecosystem.allocation.model.enums.WardType;
import com.healthcare.ecosystem.allocation.service.BedManagementService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/v1/beds")
@Tag(name = "Bed Management", description = "Endpoints for beds inventory, maintenance, and search")
public class BedController {

    private final BedManagementService bedManagementService;

    public BedController(BedManagementService bedManagementService) {
        this.bedManagementService = bedManagementService;
    }

    @PostMapping
    @Operation(summary = "Add a new bed to a room")
    public ResponseEntity<ApiResponse<BedResponse>> createBed(@Valid @RequestBody CreateBedRequest request) {
        BedResponse response = bedManagementService.createBed(request);
        return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.ok("Bed created successfully", response));
    }

    @GetMapping("/{bedId}")
    @Operation(summary = "Get bed details by ID")
    public ResponseEntity<ApiResponse<BedResponse>> getBedById(@PathVariable Long bedId) {
        return ResponseEntity.ok(ApiResponse.ok(bedManagementService.getBedById(bedId)));
    }

    @GetMapping("/available")
    @Operation(summary = "Search available beds. Every filter is optional; "
            + "patientGender matches the ward's gender policy.")
    public ResponseEntity<ApiResponse<List<BedResponse>>> searchAvailableBeds(
            @RequestParam(required = false) Long hospitalId,
            @RequestParam(required = false) Long wardId,
            @RequestParam(required = false) BedType bedType,
            @RequestParam(required = false) Boolean isolationRequired,
            @RequestParam(required = false) WardType wardType,
            @RequestParam(required = false) PatientGender patientGender) {
        List<BedResponse> response = bedManagementService.searchAvailableBeds(
                hospitalId, wardId, bedType, isolationRequired, wardType, patientGender);
        return ResponseEntity.ok(ApiResponse.ok(response));
    }

    @PostMapping("/{bedId}/maintenance/complete")
    @Operation(summary = "Complete bed maintenance/sanitization, mark AVAILABLE and re-evaluate waiting list")
    public ResponseEntity<ApiResponse<BedResponse>> completeMaintenance(@PathVariable Long bedId) {
        BedResponse response = bedManagementService.completeMaintenance(bedId);
        return ResponseEntity.ok(
                ApiResponse.ok("Bed maintenance completed. Bed is now AVAILABLE", response));
    }

    @PostMapping("/{bedId}/block")
    @Operation(summary = "Block a bed (administrative hold, e.g. repair or deep clean)")
    public ResponseEntity<ApiResponse<BedResponse>> blockBed(
            @PathVariable Long bedId,
            @RequestParam(required = false, defaultValue = "Administrative hold") String reason) {
        BedResponse response = bedManagementService.blockBed(bedId, reason);
        return ResponseEntity.ok(ApiResponse.ok("Bed blocked", response));
    }

    @GetMapping("/stats")
    @Operation(summary = "Bed counts grouped by status")
    public ResponseEntity<ApiResponse<Map<String, Long>>> bedStats() {
        Map<String, Long> stats = new java.util.LinkedHashMap<>();
        for (BedStatus status : BedStatus.values()) {
            stats.put(status.name(), bedManagementService.countBedsByStatus(status));
        }
        return ResponseEntity.ok(ApiResponse.ok(stats));
    }
}