package com.cfs.BMS.service;


import com.cfs.BMS.dto.BookingRequest;
import com.cfs.BMS.dto.BookingResponse;
import com.cfs.BMS.dto.SeatStatusResponse;
import com.cfs.BMS.entity.*;
import com.cfs.BMS.enums.BookingStatus;
import com.cfs.BMS.enums.SeatHoldStatus;
import com.cfs.BMS.exception.ConflictException;
import com.cfs.BMS.exception.ForbiddenException;
import com.cfs.BMS.exception.HoldExpiredException;
import com.cfs.BMS.exception.NotFoundException;
import com.cfs.BMS.exception.SeatAlreadyBookedException;
import com.cfs.BMS.repository.BookingRepository;
import com.cfs.BMS.repository.SeatRepository;
import com.cfs.BMS.repository.ShowSeatRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.*;

/**
 * Seat lifecycle per show:  AVAILABLE --hold--> HELD --confirm--> BOOKED --cancel--> AVAILABLE
 *                                          \--expire/cancel--> AVAILABLE
 * Booking lifecycle:        PENDING --confirm--> CONFIRMED --cancel--> CANCELLED
 *                           PENDING --expire--> EXPIRED,  PENDING --cancel--> CANCELLED
 *
 * Concurrency rules (apply to every method below):
 *  - Seat rows (show_seats) are locked with SELECT ... FOR UPDATE, in ascending seat-id order.
 *  - Lock order is always show_seats first, then the booking row. Nothing takes them the other way round,
 *    so hold/confirm/cancel/expire cannot deadlock each other.
 *  - UNIQUE(show_id, seat_id), the CHECK constraints from V2 and @Version on ShowSeat/Booking are the
 *    backstop if a code path ever forgets to lock.
 *  - Expiry is enforced against the clock at read/confirm time, so correctness never depends on the
 *    scheduled sweeper having run (e.g. right after a restart).
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class BookingService {

    /** Max rows released per sweeper transaction. */
    public static final int EXPIRY_BATCH_SIZE = 500;

    private final BookingRepository bookingRepository;
    private final SeatRepository seatRepository;
    private final ShowSeatRepository showSeatRepository;
    private final UserService userService;
    private final ShowService showService;

    @Value("${app.booking.hold-duration:PT5M}")
    private Duration holdDuration = Duration.ofMinutes(5);

    // ------------------------------------------------------------------ hold / book

    /** Holds the seats for {@code app.booking.hold-duration} (default 5 minutes). Returns a PENDING booking. */
    @Transactional
    public BookingResponse holdSeats(BookingRequest request) {
        return BookingResponse.from(placeBooking(request, false));
    }

    /** Direct booking: hold + confirm in a single transaction (kept for backward compatibility). */
    @Transactional
    public BookingResponse createBooking(BookingRequest request) {
        return BookingResponse.from(placeBooking(request, true));
    }

    private Booking placeBooking(BookingRequest request, boolean confirmNow) {
        if (request.getSeatIds() == null || request.getSeatIds().isEmpty())
            throw new IllegalArgumentException("seatIds must not be empty");
        Set<Long> distinct = new TreeSet<>(request.getSeatIds());
        if (distinct.size() != request.getSeatIds().size())
            throw new IllegalArgumentException("Duplicate seatIds in request");

        UUID key = request.getIdempotencyKey();
        if (key != null) {
            Optional<Booking> existing = bookingRepository.findByIdempotencyKey(key);
            if (existing.isPresent()) {
                if (!existing.get().getUser().getId().equals(request.getUserId()))
                    throw new ConflictException("Idempotency key already used");
                return existing.get();
            }
        }

        User user = userService.getUserById(request.getUserId());
        Show show = showService.getShowById(request.getShowId());
        List<Long> seatIds = List.copyOf(distinct);

        List<ShowSeat> rows = lockInventory(show, seatIds);
        Instant now = Instant.now();
        for (ShowSeat ss : rows) {
            if (!ss.getSeat().getScreen().getId().equals(show.getScreen().getId()))
                throw new IllegalArgumentException("Seat " + ss.getSeat().getId() + " does not belong to this show's screen");
            requireSeatFree(ss, now);
        }

        List<Seat> seats = rows.stream().map(ShowSeat::getSeat).toList();
        BigDecimal total = show.getTicketPrice() == null ? BigDecimal.ZERO
                : BigDecimal.valueOf(show.getTicketPrice()).multiply(BigDecimal.valueOf(seats.size()));
        Instant holdUntil = now.plus(holdDuration);

        Booking booking = Booking.builder()
                .user(user).show(show).seats(new ArrayList<>(seats))
                .totalPrice(total)
                .status(confirmNow ? BookingStatus.CONFIRMED : BookingStatus.PENDING)
                .holdExpiresAt(confirmNow ? null : holdUntil)
                .confirmedAt(confirmNow ? now : null)
                .idempotencyKey(key != null ? key : UUID.randomUUID())
                .build();
        try {
            booking = bookingRepository.saveAndFlush(booking);
        } catch (DataIntegrityViolationException e) {
            throw new SeatAlreadyBookedException("Seats were just taken by another request — please pick others");
        }

        for (ShowSeat ss : rows) {
            ss.setStatus(confirmNow ? SeatHoldStatus.BOOKED : SeatHoldStatus.HELD);
            ss.setHoldExpiresAt(confirmNow ? null : holdUntil);
            ss.setBooking(booking);
        }
        showSeatRepository.saveAllAndFlush(rows);
        return booking;
    }

    /** Locks the requested show_seats rows, creating missing inventory first if the show predates it. */
    private List<ShowSeat> lockInventory(Show show, List<Long> seatIds) {
        List<ShowSeat> rows = showSeatRepository.lockByShowIdAndSeatIds(show.getId(), seatIds);
        if (rows.size() != seatIds.size()) {
            showService.ensureInventory(show);
            rows = showSeatRepository.lockByShowIdAndSeatIds(show.getId(), seatIds);
            if (rows.size() != seatIds.size())
                throw new NotFoundException("Some seats are invalid for this show");
        }
        return rows;
    }

    /** A seat can be taken if AVAILABLE, or HELD with a hold that already lapsed (reclaimed lazily). */
    private void requireSeatFree(ShowSeat ss, Instant now) {
        switch (ss.getStatus()) {
            case AVAILABLE -> { }
            case HELD -> {
                if (ss.getHoldExpiresAt() != null && !ss.getHoldExpiresAt().isAfter(now)) return;
                throw new SeatAlreadyBookedException(
                        "Seat with id " + ss.getSeat().getId() + " is currently held by another user");
            }
            case BOOKED -> throw new SeatAlreadyBookedException(
                    "Seat with id " + ss.getSeat().getId() + " is already booked");
        }
    }

    // ------------------------------------------------------------------ confirm

    /**
     * Confirms a hold. Succeeds only for a PENDING booking whose hold has not expired and whose seats are
     * all still HELD by it. If the hold has lapsed, the booking is expired and its seats released
     * (that change is committed, hence noRollbackFor) and a conflict is reported.
     * Repeating the call for an already CONFIRMED booking returns it unchanged.
     */
    @Transactional(noRollbackFor = HoldExpiredException.class)
    public BookingResponse confirmBooking(Long bookingId) {
        List<ShowSeat> owned = showSeatRepository.lockByBookingId(bookingId);
        Booking booking = bookingRepository.findByIdForUpdate(bookingId)
                .orElseThrow(() -> new NotFoundException("Booking not found with id: " + bookingId));

        switch (booking.getStatus()) {
            case CONFIRMED -> { return BookingResponse.from(booking); }
            case CANCELLED -> throw new ConflictException("Booking " + bookingId + " was cancelled");
            case EXPIRED -> throw new HoldExpiredException("Hold for booking " + bookingId + " has expired");
            case PENDING -> { }
        }

        Instant now = Instant.now();
        if (booking.getHoldExpiresAt() == null || !booking.getHoldExpiresAt().isAfter(now)) {
            expire(booking, owned);
            throw new HoldExpiredException("Hold for booking " + bookingId + " has expired");
        }
        if (owned.size() != booking.getSeats().size()
                || owned.stream().anyMatch(ss -> ss.getStatus() != SeatHoldStatus.HELD)) {
            throw new ConflictException("Seat hold for booking " + bookingId + " is no longer valid");
        }

        for (ShowSeat ss : owned) {
            ss.setStatus(SeatHoldStatus.BOOKED);
            ss.setHoldExpiresAt(null);
        }
        booking.setStatus(BookingStatus.CONFIRMED);
        booking.setConfirmedAt(now);
        showSeatRepository.saveAllAndFlush(owned);
        return BookingResponse.from(bookingRepository.saveAndFlush(booking));
    }

    // ------------------------------------------------------------------ cancel

    /**
     * Cancels a CONFIRMED booking or a PENDING hold and releases its seats. Idempotent for CANCELLED.
     * An EXPIRED booking cannot be cancelled.
     */
    @Transactional
    public BookingResponse cancelBooking(Long bookingId) {
        List<ShowSeat> owned = showSeatRepository.lockByBookingId(bookingId);
        Booking booking = bookingRepository.findByIdForUpdate(bookingId)
                .orElseThrow(() -> new NotFoundException("Booking not found with id: " + bookingId));

        switch (booking.getStatus()) {
            case CANCELLED -> { return BookingResponse.from(booking); }
            case EXPIRED -> throw new ConflictException("Booking " + bookingId + " has expired and cannot be cancelled");
            case PENDING, CONFIRMED -> { }
        }

        release(owned);
        booking.setStatus(BookingStatus.CANCELLED);
        booking.setCancelledAt(Instant.now());
        showSeatRepository.saveAllAndFlush(owned);
        return BookingResponse.from(bookingRepository.saveAndFlush(booking));
    }

    /** Back-compat alias (old typo). */
    @Transactional
    public BookingResponse cancelbooking(Long bookingId) {
        return cancelBooking(bookingId);
    }

    // ------------------------------------------------------------------ expiry sweeper

    /**
     * Returns expired, unconfirmed holds to AVAILABLE and marks their bookings EXPIRED.
     * Processes at most {@link #EXPIRY_BATCH_SIZE} seats and bookings per call (callers loop while the
     * result is >= the batch size). Rows locked by in-flight requests are skipped, not waited for.
     * All state lives in the database, so this is also what recovers holds after an application restart.
     *
     * @return number of seats + bookings changed
     */
    @Transactional
    public int releaseExpiredHolds() {
        Instant now = Instant.now();
        Pageable batch = PageRequest.of(0, EXPIRY_BATCH_SIZE);

        List<ShowSeat> seats = showSeatRepository.lockExpiredHolds(SeatHoldStatus.HELD, now, batch);
        release(seats);
        showSeatRepository.saveAllAndFlush(seats);

        List<Booking> bookings = bookingRepository.lockExpiredHolds(BookingStatus.PENDING, now, batch);
        bookings.forEach(b -> b.setStatus(BookingStatus.EXPIRED));
        bookingRepository.saveAllAndFlush(bookings);

        int changed = seats.size() + bookings.size();
        if (changed > 0) log.info("Released {} expired seat hold(s), expired {} booking(s)", seats.size(), bookings.size());
        return changed;
    }

    private void expire(Booking booking, List<ShowSeat> owned) {
        release(owned);
        booking.setStatus(BookingStatus.EXPIRED);
        showSeatRepository.saveAllAndFlush(owned);
        bookingRepository.saveAndFlush(booking);
    }

    private void release(List<ShowSeat> rows) {
        for (ShowSeat ss : rows) {
            ss.setStatus(SeatHoldStatus.AVAILABLE);
            ss.setHoldExpiresAt(null);
            ss.setBooking(null);
        }
    }

    // ------------------------------------------------------------------ reads / history

    @Transactional(readOnly = true)
    public BookingResponse getBookingById(Long id) {
        return BookingResponse.from(bookingRepository.findById(id)
                .orElseThrow(() -> new NotFoundException("Booking not found with id: " + id)));
    }

    /** Booking history of a user (all statuses), newest first unless the pageable says otherwise. */
    @Transactional(readOnly = true)
    public Page<BookingResponse> getBookingByUser(Long userId, Pageable pageable) {
        return bookingRepository.findByUserId(userId, pageable).map(BookingResponse::from);
    }

    /** Legacy non-paged variant. */
    @Transactional(readOnly = true)
    public List<BookingResponse> getBookingByUser(Long userId) {
        return getBookingByUser(userId, Pageable.unpaged()).getContent();
    }

    @Transactional(readOnly = true)
    public List<Seat> getAvailableSeats(Long showId) {
        Show show = showService.getShowById(showId);
        List<Seat> allSeats = seatRepository.findByScreenId(show.getScreen().getId());
        List<ShowSeat> rows = showSeatRepository.findWithSeatByShowId(showId);
        if (rows.isEmpty()) return allSeats;           // inventory not created yet: nothing is held or booked
        Instant now = Instant.now();
        Set<Long> unavailable = new HashSet<>();
        rows.forEach(r -> { if (effectiveStatus(r, now) != SeatHoldStatus.AVAILABLE) unavailable.add(r.getSeat().getId()); });
        return allSeats.stream().filter(s -> !unavailable.contains(s.getId())).toList();
    }

    /** Status of every seat of the show; lapsed holds are reported as AVAILABLE. */
    @Transactional(readOnly = true)
    public List<SeatStatusResponse> getSeatStatuses(Long showId) {
        Show show = showService.getShowById(showId);
        Map<Long, ShowSeat> byseat = new HashMap<>();
        showSeatRepository.findWithSeatByShowId(showId).forEach(r -> byseat.put(r.getSeat().getId(), r));
        Instant now = Instant.now();
        return seatRepository.findByScreenId(show.getScreen().getId()).stream()
                .sorted(Comparator.comparing(Seat::getId))
                .map(seat -> {
                    ShowSeat r = byseat.get(seat.getId());
                    SeatHoldStatus st = r == null ? SeatHoldStatus.AVAILABLE : effectiveStatus(r, now);
                    return new SeatStatusResponse(seat.getId(), seat.getSeatNumber(), seat.getRow(), seat.getCol(),
                            seat.getSeatType(), st, st == SeatHoldStatus.HELD ? r.getHoldExpiresAt() : null);
                }).toList();
    }

    private SeatHoldStatus effectiveStatus(ShowSeat r, Instant now) {
        if (r.getStatus() == SeatHoldStatus.HELD && r.getHoldExpiresAt() != null && !r.getHoldExpiresAt().isAfter(now))
            return SeatHoldStatus.AVAILABLE;
        return r.getStatus();
    }

    // ------------------------------------------------------------------ ownership checks (used by the controller)

    @Transactional(readOnly = true)
    public void assertBookingOwner(Long bookingId, String callerEmail) {
        Booking b = bookingRepository.findById(bookingId)
                .orElseThrow(() -> new NotFoundException("Booking not found with id: " + bookingId));
        if (!b.getUser().getEmail().equalsIgnoreCase(callerEmail))
            throw new ForbiddenException("Booking " + bookingId + " belongs to another user");
    }

    @Transactional(readOnly = true)
    public void assertUserIsCaller(Long userId, String callerEmail) {
        if (!userService.getUserById(userId).getEmail().equalsIgnoreCase(callerEmail))
            throw new ForbiddenException("Cannot act on behalf of another user");
    }
}
