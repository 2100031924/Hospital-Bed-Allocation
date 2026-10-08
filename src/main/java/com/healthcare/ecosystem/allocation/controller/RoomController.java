package com.healthcare.ecosystem.allocation.controller;

import com.healthcare.ecosystem.allocation.dto.request.CreateRoomRequest;
import com.healthcare.ecosystem.allocation.dto.response.ApiResponse;
import com.healthcare.ecosystem.allocation.dto.response.RoomResponse;
import com.healthcare.ecosystem.allocation.service.BedManagementService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/v1/rooms")
@Tag(name = "Room Management", description = "Rooms within a ward, including isolation rooms")
public class RoomController {

    private final BedManagementService bedManagementService;

    public RoomController(BedManagementService bedManagementService) {
        this.bedManagementService = bedManagementService;
    }

    @PostMapping
    @Operation(summary = "Create a room inside a ward")
    public ResponseEntity<ApiResponse<RoomResponse>> createRoom(@Valid @RequestBody CreateRoomRequest request) {
        RoomResponse response = bedManagementService.createRoom(request);
        return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.ok("Room created successfully", response));
    }

    @GetMapping("/{roomId}")
    @Operation(summary = "Get room details by ID")
    public ResponseEntity<ApiResponse<RoomResponse>> getRoom(@PathVariable Long roomId) {
        return ResponseEntity.ok(ApiResponse.ok(bedManagementService.getRoom(roomId)));
    }

    @GetMapping("/ward/{wardId}")
    @Operation(summary = "List all rooms in a ward")
    public ResponseEntity<ApiResponse<List<RoomResponse>>> getRoomsByWard(@PathVariable Long wardId) {
        return ResponseEntity.ok(ApiResponse.ok(bedManagementService.getRoomsByWard(wardId)));
    }
}