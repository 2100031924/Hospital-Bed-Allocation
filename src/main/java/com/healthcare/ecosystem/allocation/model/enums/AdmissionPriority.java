package com.healthcare.ecosystem.allocation.model.enums;

public enum AdmissionPriority {
    EMERGENCY(300),
    URGENT(200),
    NORMAL(100);

    private final int weight;

    AdmissionPriority(int weight) {
        this.weight = weight;
    }

    public int getWeight() {
        return weight;
    }
}