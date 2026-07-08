package com.orderplatform.inventory.service;

/** Outcome of attempting to reserve every line on an order; {@code rejectionReason} is set only when {@code reserved} is false. */
public record ReservationResult(boolean reserved, String rejectionReason) {

    public static ReservationResult success() {
        return new ReservationResult(true, null);
    }

    public static ReservationResult rejected(String reason) {
        return new ReservationResult(false, reason);
    }
}
