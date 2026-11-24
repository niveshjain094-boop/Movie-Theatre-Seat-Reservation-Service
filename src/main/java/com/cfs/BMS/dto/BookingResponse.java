package com.cfs.BMS.dto;

import com.cfs.BMS.entity.Booking;
import com.cfs.BMS.entity.Seat;
import com.cfs.BMS.enums.BookingStatus;
import com.cfs.BMS.enums.SeatType;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;

/**
 * Booking as exposed by the API. Built inside the service transaction, so it never leaks lazy JPA proxies.
 * A PENDING booking whose hold has lapsed is reported as EXPIRED even if the sweeper has not run yet,
 * so history is accurate to the second.
 */
public record BookingResponse(
        Long id,
        Long userId,
        Long showId,
        Long movieId,
        String movieTitle,
        LocalDate showDate,
        LocalTime startTime,
        List<SeatInfo> seats,
        BigDecimal totalPrice,
        BookingStatus status,
        UUID idempotencyKey,
        LocalDateTime bookedAt,
        Instant holdExpiresAt,
        Instant confirmedAt,
        Instant cancelledAt) {

    public record SeatInfo(Long id, String seatNumber, String row, Integer col, SeatType seatType) {
        static SeatInfo from(Seat s) {
            return new SeatInfo(s.getId(), s.getSeatNumber(), s.getRow(), s.getCol(), s.getSeatType());
        }
    }

    public static BookingResponse from(Booking b) {
        BookingStatus status = b.getStatus();
        if (status == BookingStatus.PENDING && b.getHoldExpiresAt() != null
                && !b.getHoldExpiresAt().isAfter(Instant.now())) {
            status = BookingStatus.EXPIRED;
        }
        return new BookingResponse(
                b.getId(),
                b.getUser().getId(),
                b.getShow().getId(),
                b.getShow().getMovie() == null ? null : b.getShow().getMovie().getId(),
                b.getShow().getMovie() == null ? null : b.getShow().getMovie().getTitle(),
                b.getShow().getShowDate(),
                b.getShow().getStartTime(),
                b.getSeats().stream()
                        .sorted(Comparator.comparing(Seat::getId))
                        .map(SeatInfo::from).toList(),
                b.getTotalPrice(),
                status,
                b.getIdempotencyKey(),
                b.getBookedAt(),
                b.getHoldExpiresAt(),
                b.getConfirmedAt(),
                b.getCancelledAt());
    }
}
