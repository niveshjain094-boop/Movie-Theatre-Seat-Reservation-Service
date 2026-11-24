package com.cfs.BMS.exception;

public class HoldExpiredException extends ConflictException {
    public HoldExpiredException(String message) { super(message); }
}
