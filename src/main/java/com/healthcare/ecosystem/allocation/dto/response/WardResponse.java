package com.healthcare.ecosystem.allocation.dto.response;

import com.healthcare.ecosystem.allocation.model.enums.GenderPolicy;
import com.healthcare.ecosystem.allocation.model.enums.WardType;

public record WardResponse(
    Long id,
    Long hospitalId,
    String name,
    WardType wardType,
    GenderPolicy genderPolicy
) {}