package com.cfs.BMS.controller;


import com.cfs.BMS.dto.BookingRequest;
import com.cfs.BMS.dto.BookingResponse;
import com.cfs.BMS.dto.SeatStatusResponse;
import com.cfs.BMS.entity.Seat;
import com.cfs.BMS.service.BookingService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.web.PageableDefault;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping({"/api/bookings", "/api/v1/bookings"})
@RequiredArgsConstructor
@Tag(name = "Booking Management", description = "Endpoints for holding seats and creating, confirming and cancelling movie ticket bookings")
public class BookingController {

    private final BookingService bookingService;

    @PostMapping("/hold")
    @Operation(summary = "Hold seats for 5 minutes (creates a PENDING booking). Confirm before the hold expires.")
    public ResponseEntity<BookingResponse> holdSeats(@Valid @RequestBody BookingRequest request,
                                                     @RequestHeader(value = "Idempotency-Key", required = false) String idemHeader,
                                                     Authentication auth) {
        applyIdempotencyHeader(request, idemHeader);
        bookingService.assertUserIsCaller(request.getUserId(), auth.getName());
        return ResponseEntity.status(HttpStatus.CREATED).body(bookingService.holdSeats(request));
    }

    @PostMapping("/{id}/confirm")
    @Operation(summary = "Confirm a held booking. Fails with 409 if the hold expired or was released.")
    public ResponseEntity<BookingResponse> confirmBooking(@PathVariable Long id, Authentication auth) {
        bookingService.assertBookingOwner(id, auth.getName());
        return ResponseEntity.ok(bookingService.confirmBooking(id));
    }

    @PostMapping
    @Operation(summary = "Create a confirmed booking in one step (hold + confirm; idempotent via Idempotency-Key or body key)")
    public ResponseEntity<BookingResponse> createBooking(@Valid @RequestBody BookingRequest request,
                                                         @RequestHeader(value = "Idempotency-Key", required = false) String idemHeader,
                                                         Authentication auth) {
        applyIdempotencyHeader(request, idemHeader);
        bookingService.assertUserIsCaller(request.getUserId(), auth.getName());
        return ResponseEntity.status(HttpStatus.CREATED).body(bookingService.createBooking(request));
    }

    @GetMapping("/{id}")
    @Operation(summary = "Get booking by ID (owner only)")
    public ResponseEntity<BookingResponse> getBookingById(@PathVariable Long id, Authentication auth) {
        bookingService.assertBookingOwner(id, auth.getName());
        return ResponseEntity.ok(bookingService.getBookingById(id));
    }

    @GetMapping("/user/{userId}")
    @Operation(summary = "Booking history of a user, all statuses, newest first (paginated; owner only)")
    public ResponseEntity<Page<BookingResponse>> getBookingByUserId(
            @PathVariable Long userId,
            @PageableDefault(size = 20, sort = "bookedAt", direction = Sort.Direction.DESC) Pageable pageable,
            Authentication auth) {
        bookingService.assertUserIsCaller(userId, auth.getName());
        return ResponseEntity.ok(bookingService.getBookingByUser(userId, pageable));
    }

    @PutMapping("/{id}/cancel")
    @Operation(summary = "Cancel a confirmed booking or a pending hold and release its seats")
    public ResponseEntity<BookingResponse> cancelBooking(@PathVariable Long id, Authentication auth) {
        bookingService.assertBookingOwner(id, auth.getName());
        return ResponseEntity.ok(bookingService.cancelBooking(id));
    }

    @GetMapping("/show/{showId}/available-seats")
    @Operation(summary = "Get available seats for a show")
    public ResponseEntity<List<Seat>> getAvailableSeats(@PathVariable Long showId) {
        return ResponseEntity.ok(bookingService.getAvailableSeats(showId));
    }

    @GetMapping("/show/{showId}/seats")
    @Operation(summary = "Status (AVAILABLE / HELD / BOOKED) of every seat of a show")
    public ResponseEntity<List<SeatStatusResponse>> getSeatStatuses(@PathVariable Long showId) {
        return ResponseEntity.ok(bookingService.getSeatStatuses(showId));
    }

    private void applyIdempotencyHeader(BookingRequest request, String idemHeader) {
        if (request.getIdempotencyKey() == null && idemHeader != null) {
            try { request.setIdempotencyKey(java.util.UUID.fromString(idemHeader)); }
            catch (IllegalArgumentException ignored) { }
        }
    }
}
