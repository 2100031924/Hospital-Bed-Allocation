package com.healthcare.ecosystem.allocation.exception;

public class BedUnavailableException extends RuntimeException {
    public BedUnavailableException(String message) {
        super(message);
    }
}