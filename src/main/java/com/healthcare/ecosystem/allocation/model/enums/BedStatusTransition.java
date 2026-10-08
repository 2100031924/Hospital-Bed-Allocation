package com.healthcare.ecosystem.allocation.model.enums;


public enum BedStatusTransition {

    AVAILABLE_RESERVED(BedStatus.AVAILABLE, BedStatus.RESERVED),
    AVAILABLE_BLOCKED(BedStatus.AVAILABLE, BedStatus.BLOCKED),
    AVAILABLE_MAINTENANCE(BedStatus.AVAILABLE, BedStatus.MAINTENANCE),
    RESERVED_OCCUPIED(BedStatus.RESERVED, BedStatus.OCCUPIED),
    RESERVED_AVAILABLE(BedStatus.RESERVED, BedStatus.AVAILABLE),
    RESERVED_BLOCKED(BedStatus.RESERVED, BedStatus.BLOCKED),
    OCCUPIED_MAINTENANCE(BedStatus.OCCUPIED, BedStatus.MAINTENANCE),
    OCCUPIED_BLOCKED(BedStatus.OCCUPIED, BedStatus.BLOCKED),
    MAINTENANCE_AVAILABLE(BedStatus.MAINTENANCE, BedStatus.AVAILABLE),
    MAINTENANCE_BLOCKED(BedStatus.MAINTENANCE, BedStatus.BLOCKED),
    BLOCKED_AVAILABLE(BedStatus.BLOCKED, BedStatus.AVAILABLE),
    BLOCKED_MAINTENANCE(BedStatus.BLOCKED, BedStatus.MAINTENANCE);

    private final BedStatus from;
    private final BedStatus to;

    BedStatusTransition(BedStatus from, BedStatus to) {
        this.from = from;
        this.to = to;
    }

    public BedStatus getFrom() {
        return from;
    }

    public BedStatus getTo() {
        return to;
    }


    public static boolean isAllowed(BedStatus from, BedStatus to) {
        if (from == to) {
            return false;
        }
        for (BedStatusTransition transition : values()) {
            if (transition.from == from && transition.to == to) {
                return true;
            }
        }
        return false;
    }


    public static boolean isAllowedToReceivePatient(BedStatus status) {
        return status == BedStatus.AVAILABLE;
    }


    public static boolean isOccupiedOrHeld(BedStatus status) {
        return status == BedStatus.OCCUPIED || status == BedStatus.RESERVED;
    }


    public static boolean isOutOfService(BedStatus status) {
        return status == BedStatus.MAINTENANCE || status == BedStatus.BLOCKED;
    }
}