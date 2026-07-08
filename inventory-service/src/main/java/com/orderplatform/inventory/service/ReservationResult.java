package com.orderplatform.inventory.service;

public record ReservationResult(boolean reserved, String rejectionReason) {

    public static ReservationResult success() {
        return new ReservationResult(true, null);
    }

    public static ReservationResult rejected(String reason) {
        return new ReservationResult(false, reason);
    }
}
