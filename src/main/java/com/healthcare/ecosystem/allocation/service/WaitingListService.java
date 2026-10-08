package com.healthcare.ecosystem.allocation.service;

import com.healthcare.ecosystem.allocation.model.entity.Admission;
import com.healthcare.ecosystem.allocation.model.entity.Bed;

public interface WaitingListService {
    void enqueue(Admission admission);

    void evaluateWaitingListForBed(Bed releasedBed);
}