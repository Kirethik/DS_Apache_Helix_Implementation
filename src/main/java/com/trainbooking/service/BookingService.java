package com.trainbooking.service;

import com.trainbooking.model.Seat;
import com.trainbooking.model.SeatStatus;
import com.trainbooking.model.BookingResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * BookingService — Core booking logic for a single train partition.
 *
 * Only the MASTER node for a given train runs an active BookingService.
 * SLAVE nodes hold a replicated copy (read-only).
 *
 * Supports three use cases:
 * UC1 — Normal seat booking : bookSeat() → confirmSeat()
 * UC2 — Reservation expiry : expireSeat()
 * UC3 — Cancellation : cancelSeat()
 */
public class BookingService {

    private static final Logger log = LoggerFactory.getLogger(BookingService.class);

    private final String trainId;
    private final Map<Integer, Seat> seats = new ConcurrentHashMap<>();

    public BookingService(String trainId, int totalSeats) {
        this.trainId = trainId;
        for (int i = 1; i <= totalSeats; i++) {
            seats.put(i, new Seat(i));
        }
        log.info("[{}] BookingService initialized with {} seats.", trainId, totalSeats);
    }

    // ─────────────────────────────────────────────────────────────────
    // Use Case 1 — Normal Booking
    // ─────────────────────────────────────────────────────────────────

    /**
     * UC1-Step1: Reserve a seat (AVAILABLE → RESERVED).
     */
    public BookingResult reserveSeat(int seatNumber, String passengerName) {
        Seat seat = seats.get(seatNumber);
        if (seat == null) {
            return new BookingResult(false, "Seat does not exist", trainId, seatNumber, null);
        }
        if (seat.getStatus() != SeatStatus.AVAILABLE) {
            return new BookingResult(false,
                    "Seat not available (current: " + seat.getStatus() + ")",
                    trainId, seatNumber, seat.getStatus());
        }
        boolean ok = seat.transition(SeatStatus.AVAILABLE, SeatStatus.RESERVED);
        if (ok) {
            seat.setPassengerName(passengerName);
            log.info("[{}] Seat {} RESERVED for '{}'", trainId, seatNumber, passengerName);
            return new BookingResult(true, "Seat reserved successfully",
                    trainId, seatNumber, SeatStatus.RESERVED);
        }
        return new BookingResult(false, "Concurrent reservation conflict (retry)",
                trainId, seatNumber, seat.getStatus());
    }

    /**
     * UC1-Step2: Confirm a reserved seat (RESERVED → CONFIRMED).
     */
    public BookingResult confirmSeat(int seatNumber) {
        Seat seat = seats.get(seatNumber);
        if (seat == null) {
            return new BookingResult(false, "Seat does not exist", trainId, seatNumber, null);
        }
        boolean ok = seat.transition(SeatStatus.RESERVED, SeatStatus.CONFIRMED);
        if (ok) {
            log.info("[{}] Seat {} CONFIRMED for '{}'", trainId, seatNumber, seat.getPassengerName());
            return new BookingResult(true, "Booking confirmed",
                    trainId, seatNumber, SeatStatus.CONFIRMED);
        }
        return new BookingResult(false,
                "Cannot confirm — seat is not in RESERVED state (current: " + seat.getStatus() + ")",
                trainId, seatNumber, seat.getStatus());
    }

    // ─────────────────────────────────────────────────────────────────
    // Use Case 2 — Reservation Expiry
    // ─────────────────────────────────────────────────────────────────

    /**
     * UC2: Expire a reserved seat if payment window elapsed (RESERVED → EXPIRED).
     */
    public BookingResult expireSeat(int seatNumber) {
        Seat seat = seats.get(seatNumber);
        if (seat == null) {
            return new BookingResult(false, "Seat does not exist", trainId, seatNumber, null);
        }
        boolean ok = seat.transition(SeatStatus.RESERVED, SeatStatus.EXPIRED);
        if (ok) {
            log.warn("[{}] Seat {} EXPIRED — released back to pool.", trainId, seatNumber);
            // Make it available again
            seat.transition(SeatStatus.EXPIRED, SeatStatus.AVAILABLE);
            return new BookingResult(true, "Seat reservation expired - seat released",
                    trainId, seatNumber, SeatStatus.AVAILABLE);
        }
        return new BookingResult(false,
                "Cannot expire — seat is not RESERVED (current: " + seat.getStatus() + ")",
                trainId, seatNumber, seat.getStatus());
    }

    // ─────────────────────────────────────────────────────────────────
    // Use Case 3 — Cancellation
    // ─────────────────────────────────────────────────────────────────

    /**
     * UC3: Cancel a confirmed seat (CONFIRMED → CANCELLED).
     */
    public BookingResult cancelSeat(int seatNumber) {
        Seat seat = seats.get(seatNumber);
        if (seat == null) {
            return new BookingResult(false, "Seat does not exist", trainId, seatNumber, null);
        }
        boolean ok = seat.transition(SeatStatus.CONFIRMED, SeatStatus.CANCELLED);
        if (ok) {
            log.warn("[{}] Seat {} CANCELLED by '{}'", trainId, seatNumber, seat.getPassengerName());
            return new BookingResult(true, "Booking cancelled",
                    trainId, seatNumber, SeatStatus.CANCELLED);
        }
        return new BookingResult(false,
                "Cannot cancel — seat is not CONFIRMED (current: " + seat.getStatus() + ")",
                trainId, seatNumber, seat.getStatus());
    }

    // ─────────────────────────────────────────────────────────────────
    // Utility
    // ─────────────────────────────────────────────────────────────────

    public Map<Integer, Seat> getAllSeats() {
        return Collections.unmodifiableMap(seats);
    }

    public Seat getSeat(int seatNumber) {
        return seats.get(seatNumber);
    }

    public List<Seat> getAvailableSeats() {
        List<Seat> available = new ArrayList<>();
        for (Seat s : seats.values()) {
            if (s.getStatus() == SeatStatus.AVAILABLE)
                available.add(s);
        }
        return available;
    }

    public String getTrainId() {
        return trainId;
    }

    public void printStatus() {
        System.out.println("\n  ┌─ Seat Status for " + trainId + " ─────────────────");
        seats.entrySet().stream()
                .sorted(Map.Entry.comparingByKey())
                .forEach(e -> System.out.println("  │ " + e.getValue()));
        System.out.println("  └─────────────────────────────────────────────");
    }
}
