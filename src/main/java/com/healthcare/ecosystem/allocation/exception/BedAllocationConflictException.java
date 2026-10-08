package com.healthcare.ecosystem.allocation.exception;

import jakarta.persistence.OptimisticLockException;
import jakarta.persistence.PessimisticLockException;


public class BedAllocationConflictException extends RuntimeException {
    public BedAllocationConflictException(String message) {
        super(message);
    }

    public BedAllocationConflictException(String message, Throwable cause) {
        super(message, cause);
    }

    public static BedAllocationConflictException from(Throwable cause) {
        if (cause instanceof OptimisticLockException || cause instanceof PessimisticLockException) {
            return new BedAllocationConflictException(
                    "This bed is being allocated by another concurrent request. Please retry.", cause);
        }
        return new BedAllocationConflictException("Resource contention detected. Please retry.", cause);
    }
}