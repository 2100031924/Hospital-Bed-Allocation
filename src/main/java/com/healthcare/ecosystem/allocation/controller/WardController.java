package com.healthcare.ecosystem.allocation.controller;

import com.healthcare.ecosystem.allocation.dto.request.CreateWardRequest;
import com.healthcare.ecosystem.allocation.dto.response.ApiResponse;
import com.healthcare.ecosystem.allocation.dto.response.BedResponse;
import com.healthcare.ecosystem.allocation.dto.response.WardResponse;
import com.healthcare.ecosystem.allocation.service.BedManagementService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/v1/wards")
@Tag(name = "Ward Management", description = "Endpoints for managing hospital wards and inventory")
public class WardController {

    private final BedManagementService bedManagementService;

    public WardController(BedManagementService bedManagementService) {
        this.bedManagementService = bedManagementService;
    }

    @PostMapping
    @Operation(summary = "Create a new hospital ward")
    public ResponseEntity<ApiResponse<WardResponse>> createWard(@Valid @RequestBody CreateWardRequest request) {
        WardResponse response = bedManagementService.createWard(request);
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(ApiResponse.ok("Ward created successfully", response));
    }

    @GetMapping("/{wardId}")
    @Operation(summary = "Get ward details by ID")
    public ResponseEntity<ApiResponse<WardResponse>> getWard(@PathVariable Long wardId) {
        WardResponse response = bedManagementService.getWard(wardId);
        return ResponseEntity.ok(ApiResponse.ok(response));
    }

    @GetMapping("/{wardId}/beds")
    @Operation(summary = "List all beds belonging to a specific ward")
    public ResponseEntity<ApiResponse<List<BedResponse>>> getBedsByWard(@PathVariable Long wardId) {
        List<BedResponse> response = bedManagementService.getBedsByWard(wardId);
        return ResponseEntity.ok(ApiResponse.ok(response));
    }
}