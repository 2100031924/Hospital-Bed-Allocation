package com.healthcare.ecosystem.allocation.dto.response;

public record HospitalResponse(
    Long id,
    String code,
    String name,
    String address
) {}