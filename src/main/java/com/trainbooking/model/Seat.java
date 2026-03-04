package com.trainbooking.model;

import java.time.LocalDateTime;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Represents a single seat inside a train partition.
 * Status changes are thread-safe using AtomicReference.
 */
public class Seat {

    private final int seatNumber;
    private final AtomicReference<SeatStatus> status;
    private volatile String passengerName;
    private volatile LocalDateTime reservedAt;
    private volatile LocalDateTime confirmedAt;

    public Seat(int seatNumber) {
        this.seatNumber = seatNumber;
        this.status = new AtomicReference<>(SeatStatus.AVAILABLE);
    }

    public int getSeatNumber()    { return seatNumber; }
    public SeatStatus getStatus() { return status.get(); }
    public String getPassengerName() { return passengerName; }
    public LocalDateTime getReservedAt()  { return reservedAt; }
    public LocalDateTime getConfirmedAt() { return confirmedAt; }

    /**
     * Atomically transitions status from expected to target.
     * @return true if transition succeeded, false otherwise.
     */
    public boolean transition(SeatStatus expected, SeatStatus target) {
        if (!expected.canTransitionTo(target)) {
            return false;
        }
        boolean success = status.compareAndSet(expected, target);
        if (success) {
            if (target == SeatStatus.RESERVED)    reservedAt  = LocalDateTime.now();
            if (target == SeatStatus.CONFIRMED)   confirmedAt = LocalDateTime.now();
            if (target == SeatStatus.AVAILABLE)   { reservedAt = null; passengerName = null; }
        }
        return success;
    }

    public void setPassengerName(String name) { this.passengerName = name; }

    @Override
    public String toString() {
        return String.format("Seat[%d | %-9s | passenger=%s]",
                seatNumber, status.get(), passengerName == null ? "-" : passengerName);
    }
}
