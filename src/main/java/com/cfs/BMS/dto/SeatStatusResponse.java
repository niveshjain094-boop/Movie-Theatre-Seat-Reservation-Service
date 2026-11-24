package com.cfs.BMS.dto;

import com.cfs.BMS.enums.SeatHoldStatus;
import com.cfs.BMS.enums.SeatType;

import java.time.Instant;

/** One seat's state for one show. Does not reveal who holds or owns the seat. */
public record SeatStatusResponse(
        Long seatId,
        String seatNumber,
        String row,
        Integer col,
        SeatType seatType,
        SeatHoldStatus status,
        Instant holdExpiresAt) {
}
