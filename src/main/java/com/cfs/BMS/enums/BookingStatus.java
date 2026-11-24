package com.cfs.BMS.enums;

public enum BookingStatus {
    /** Seats are held, awaiting confirmation; expires at hold_expires_at. */
    PENDING,
    CONFIRMED,
    CANCELLED,
    /** Hold was not confirmed in time. */
    EXPIRED
}
