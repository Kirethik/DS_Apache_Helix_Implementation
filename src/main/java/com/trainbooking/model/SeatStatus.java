package com.trainbooking.model;

/**
 * SeatStatus — State machine for a single seat in the booking system.
 *
 * State Transitions:
 *   AVAILABLE  → RESERVED   (user locks a seat)
 *   RESERVED   → CONFIRMED  (payment completed)
 *   RESERVED   → EXPIRED    (payment window timed out)
 *   CONFIRMED  → CANCELLED  (user cancels after payment)
 *
 * These transitions are enforced in BookingService to prevent double-booking.
 */
public enum SeatStatus {
    AVAILABLE,
    RESERVED,
    CONFIRMED,
    CANCELLED,
    EXPIRED;

    /**
     * Returns true if a transition from this state to the target is valid.
     */
    public boolean canTransitionTo(SeatStatus target) {
        switch (this) {
            case AVAILABLE:  return target == RESERVED;
            case RESERVED:   return target == CONFIRMED || target == EXPIRED;
            case CONFIRMED:  return target == CANCELLED;
            case CANCELLED:  return false;
            case EXPIRED:    return target == AVAILABLE; // can be re-opened
            default:         return false;
        }
    }
}
