package com.trainbooking.model;

/**
 * Result returned by every booking operation.
 */
public class BookingResult {

    private final boolean success;
    private final String message;
    private final String trainId;
    private final int seatNumber;
    private final SeatStatus newStatus;

    public BookingResult(boolean success, String message,
            String trainId, int seatNumber, SeatStatus newStatus) {
        this.success = success;
        this.message = message;
        this.trainId = trainId;
        this.seatNumber = seatNumber;
        this.newStatus = newStatus;
    }

    public boolean isSuccess() {
        return success;
    }

    public String getMessage() {
        return message;
    }

    public String getTrainId() {
        return trainId;
    }

    public int getSeatNumber() {
        return seatNumber;
    }

    public SeatStatus getNewStatus() {
        return newStatus;
    }

    @Override
    public String toString() {
        return String.format("[%s] Train=%s Seat=%d Status=%s | %s",
                success ? "OK" : "FAIL", trainId, seatNumber, newStatus, message);
    }
}
