package com.healthcare.ecosystem.allocation.service;

import com.healthcare.ecosystem.allocation.model.entity.Admission;
import com.healthcare.ecosystem.allocation.model.entity.Bed;

import java.util.Optional;

public interface BedAllocationEngine {
    Optional<Bed> allocateBed(Admission admission);

    boolean isBedEligibleForAdmission(Bed bed, Admission admission);


    boolean isBedAlreadyHeld(Bed bed);
}