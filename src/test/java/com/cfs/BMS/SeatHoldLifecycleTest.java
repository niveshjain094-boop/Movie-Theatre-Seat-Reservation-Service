package com.cfs.BMS;

import com.cfs.BMS.dto.BookingRequest;
import com.cfs.BMS.dto.BookingResponse;
import com.cfs.BMS.dto.SeatStatusResponse;
import com.cfs.BMS.dto.UserRequest;
import com.cfs.BMS.entity.*;
import com.cfs.BMS.enums.BookingStatus;
import com.cfs.BMS.enums.SeatHoldStatus;
import com.cfs.BMS.enums.SeatType;
import com.cfs.BMS.exception.ConflictException;
import com.cfs.BMS.exception.HoldExpiredException;
import com.cfs.BMS.exception.SeatAlreadyBookedException;
import com.cfs.BMS.repository.*;
import com.cfs.BMS.service.BookingService;
import com.cfs.BMS.service.ShowService;
import com.cfs.BMS.service.UserService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.jdbc.core.JdbcTemplate;

import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Seat hold / confirm / expire / cancel behaviour. Every test builds its own city/theater/show,
 * so tests are independent and share one application context. The background sweeper is disabled in
 * test properties; tests call {@link BookingService#releaseExpiredHolds()} directly.
 */
@SpringBootTest
class SeatHoldLifecycleTest {

    @Autowired BookingService bookingService;
    @Autowired ShowService showService;
    @Autowired UserService userService;
    @Autowired CityRepository cityRepository;
    @Autowired TheaterRepository theaterRepository;
    @Autowired ScreenRepository screenRepository;
    @Autowired SeatRepository seatRepository;
    @Autowired MovieRepository movieRepository;
    @Autowired ShowRepository showRepository;
    @Autowired ShowSeatRepository showSeatRepository;
    @Autowired JdbcTemplate jdbc;

    // ------------------------------------------------------------------ fixtures

    record Fixture(Show show, List<Seat> seats) {
        Long seatId(int i) { return seats.get(i).getId(); }
    }

    private User newUser(String prefix) {
        return userService.register(new UserRequest(prefix, prefix + "+" + UUID.randomUUID() + "@x.com", "password123", "999"));
    }

    private Fixture fixture(int seatCount, boolean withInventory) {
        City city = cityRepository.save(City.builder().name("C" + UUID.randomUUID()).state("S").build());
        Theater theater = theaterRepository.save(Theater.builder().name("T").address("A").city(city).build());
        Screen screen = screenRepository.save(Screen.builder().name("S").totalSeats(seatCount).theater(theater).build());
        List<Seat> seats = new ArrayList<>();
        for (int i = 1; i <= seatCount; i++)
            seats.add(seatRepository.save(Seat.builder().seatNumber("A" + i).row("A").col(i)
                    .seatType(SeatType.REGULAR).screen(screen).build()));
        Movie movie = movieRepository.save(Movie.builder().title("M").genre("Action").language("Hindi").durationMinutes(120).build());
        Show show = showRepository.save(Show.builder().movie(movie).screen(screen)
                .showDate(LocalDate.now().plusDays(1)).startTime(LocalTime.of(10, 0)).endTime(LocalTime.of(12, 0))
                .ticketPrice(250.0).build());
        if (withInventory) showService.ensureInventory(show);
        return new Fixture(show, seats);
    }

    private BookingRequest req(User u, Fixture f, int... seatIdx) {
        List<Long> ids = Arrays.stream(seatIdx).mapToObj(f::seatId).toList();
        return new BookingRequest(u.getId(), f.show().getId(), ids, null);
    }

    private String seatStatus(Fixture f, int seatIdx) {
        return jdbc.queryForObject("select status from show_seats where show_id=? and seat_id=?",
                String.class, f.show().getId(), f.seatId(seatIdx));
    }

    private String bookingStatus(Long bookingId) {
        return jdbc.queryForObject("select status from bookings where id=?", String.class, bookingId);
    }

    /** Moves a hold's expiry into the past, exactly as if 5 minutes had gone by. */
    private void expireHold(Long bookingId) {
        Timestamp past = Timestamp.from(Instant.now().minus(Duration.ofMinutes(1)));
        jdbc.update("update bookings set hold_expires_at=? where id=?", past, bookingId);
        jdbc.update("update show_seats set hold_expires_at=? where booking_id=?", past, bookingId);
    }

    // ------------------------------------------------------------------ 1 & 2: statuses and hold

    @Test
    void hold_marksSeatHeldForFiveMinutes_andRemovesItFromAvailable() {
        User a = newUser("a");
        Fixture f = fixture(3, true);
        Instant before = Instant.now();

        BookingResponse h = bookingService.holdSeats(req(a, f, 0, 1));

        assertEquals(BookingStatus.PENDING, h.status());
        assertNotNull(h.holdExpiresAt());
        long seconds = Duration.between(before, h.holdExpiresAt()).getSeconds();
        assertTrue(seconds >= 299 && seconds <= 301, "hold should last 5 minutes, was " + seconds + "s");
        assertEquals("HELD", seatStatus(f, 0));
        assertEquals("HELD", seatStatus(f, 1));
        assertEquals("AVAILABLE", seatStatus(f, 2));

        List<Long> available = bookingService.getAvailableSeats(f.show().getId()).stream().map(Seat::getId).toList();
        assertEquals(List.of(f.seatId(2)), available);

        Map<Long, SeatHoldStatus> statuses = bookingService.getSeatStatuses(f.show().getId()).stream()
                .collect(Collectors.toMap(SeatStatusResponse::seatId, SeatStatusResponse::status));
        assertEquals(SeatHoldStatus.HELD, statuses.get(f.seatId(0)));
        assertEquals(SeatHoldStatus.AVAILABLE, statuses.get(f.seatId(2)));
    }

    @Test
    void heldSeat_cannotBeHeldOrBookedByAnotherUser() {
        User a = newUser("a"), b = newUser("b");
        Fixture f = fixture(2, true);
        bookingService.holdSeats(req(a, f, 0));

        assertThrows(SeatAlreadyBookedException.class, () -> bookingService.holdSeats(req(b, f, 0)));
        assertThrows(SeatAlreadyBookedException.class, () -> bookingService.createBooking(req(b, f, 0)));
        // all-or-nothing: a multi-seat request that includes a held seat takes none of them
        assertThrows(SeatAlreadyBookedException.class, () -> bookingService.holdSeats(req(b, f, 1, 0)));
        assertEquals("AVAILABLE", seatStatus(f, 1));
    }

    // ------------------------------------------------------------------ 4: confirmation

    @Test
    void confirm_validHold_booksSeats_andIsIdempotent() {
        User a = newUser("a");
        Fixture f = fixture(2, true);
        BookingResponse h = bookingService.holdSeats(req(a, f, 0, 1));

        BookingResponse c = bookingService.confirmBooking(h.id());

        assertEquals(BookingStatus.CONFIRMED, c.status());
        assertNotNull(c.confirmedAt());
        assertEquals("BOOKED", seatStatus(f, 0));
        assertEquals("BOOKED", seatStatus(f, 1));
        assertEquals(BookingStatus.CONFIRMED, bookingService.confirmBooking(h.id()).status());
        // a confirmed seat is no longer released by the sweeper
        bookingService.releaseExpiredHolds();
        assertEquals("BOOKED", seatStatus(f, 0));
    }

    @Test
    void confirm_expiredHold_isRejected_andSeatsAreReleased() {
        User a = newUser("a");
        Fixture f = fixture(1, true);
        BookingResponse h = bookingService.holdSeats(req(a, f, 0));
        expireHold(h.id());

        assertThrows(HoldExpiredException.class, () -> bookingService.confirmBooking(h.id()));

        // the release happened even though an exception was thrown (noRollbackFor)
        assertEquals("EXPIRED", bookingStatus(h.id()));
        assertEquals("AVAILABLE", seatStatus(f, 0));
        assertThrows(HoldExpiredException.class, () -> bookingService.confirmBooking(h.id()));
    }

    @Test
    void confirm_cancelledBooking_isRejected() {
        User a = newUser("a");
        Fixture f = fixture(1, true);
        BookingResponse h = bookingService.holdSeats(req(a, f, 0));
        bookingService.cancelBooking(h.id());

        assertThrows(ConflictException.class, () -> bookingService.confirmBooking(h.id()));
        assertEquals("AVAILABLE", seatStatus(f, 0));
    }

    // ------------------------------------------------------------------ 3: auto release

    @Test
    void expiredHold_isReclaimableImmediately_withoutWaitingForSweeper() {
        User a = newUser("a"), b = newUser("b");
        Fixture f = fixture(1, true);
        BookingResponse ha = bookingService.holdSeats(req(a, f, 0));
        expireHold(ha.id());

        // seat already shows as free to readers
        assertEquals(1, bookingService.getAvailableSeats(f.show().getId()).size());

        BookingResponse hb = bookingService.holdSeats(req(b, f, 0));
        assertEquals(BookingStatus.PENDING, hb.status());
        // the first user can no longer confirm, the second can
        assertThrows(HoldExpiredException.class, () -> bookingService.confirmBooking(ha.id()));
        assertEquals("HELD", seatStatus(f, 0));
        assertEquals(BookingStatus.CONFIRMED, bookingService.confirmBooking(hb.id()).status());
    }

    @Test
    void sweeper_releasesOnlyExpiredHolds_usingDatabaseStateAlone() {
        User a = newUser("a"), b = newUser("b");
        Fixture f = fixture(2, true);
        BookingResponse expired = bookingService.holdSeats(req(a, f, 0));
        BookingResponse live = bookingService.holdSeats(req(b, f, 1));
        expireHold(expired.id());

        // No in-memory state is involved: this is exactly what the startup run does after a restart.
        int changed = bookingService.releaseExpiredHolds();

        assertTrue(changed >= 2, "at least this test's one seat + one booking were released");
        assertEquals("AVAILABLE", seatStatus(f, 0));
        assertEquals("EXPIRED", bookingStatus(expired.id()));
        assertEquals("HELD", seatStatus(f, 1));
        assertEquals("PENDING", bookingStatus(live.id()));
        assertEquals(0, bookingService.releaseExpiredHolds(), "second run is a no-op");
    }

    // ------------------------------------------------------------------ 6: cancellation

    @Test
    void cancel_confirmedBooking_releasesSeats_andSeatsCanBeBookedAgain() {
        User a = newUser("a"), b = newUser("b");
        Fixture f = fixture(2, true);
        BookingResponse c = bookingService.confirmBooking(bookingService.holdSeats(req(a, f, 0, 1)).id());

        BookingResponse x = bookingService.cancelBooking(c.id());

        assertEquals(BookingStatus.CANCELLED, x.status());
        assertNotNull(x.cancelledAt());
        assertEquals("AVAILABLE", seatStatus(f, 0));
        assertEquals("AVAILABLE", seatStatus(f, 1));
        assertEquals(BookingStatus.CANCELLED, bookingService.cancelBooking(c.id()).status(), "idempotent");
        assertEquals(BookingStatus.CONFIRMED, bookingService.createBooking(req(b, f, 0, 1)).status());
    }

    @Test
    void cancel_pendingHold_releasesSeats() {
        User a = newUser("a");
        Fixture f = fixture(1, true);
        BookingResponse h = bookingService.holdSeats(req(a, f, 0));

        assertEquals(BookingStatus.CANCELLED, bookingService.cancelBooking(h.id()).status());
        assertEquals("AVAILABLE", seatStatus(f, 0));
    }

    @Test
    void cancel_expiredBooking_isRejected() {
        User a = newUser("a");
        Fixture f = fixture(1, true);
        BookingResponse h = bookingService.holdSeats(req(a, f, 0));
        expireHold(h.id());
        bookingService.releaseExpiredHolds();

        assertThrows(ConflictException.class, () -> bookingService.cancelBooking(h.id()));
    }

    @Test
    void cancellingOldBooking_doesNotReleaseSeatsNowOwnedByAnotherBooking() {
        User a = newUser("a"), b = newUser("b");
        Fixture f = fixture(1, true);
        BookingResponse ha = bookingService.holdSeats(req(a, f, 0));
        expireHold(ha.id());
        BookingResponse hb = bookingService.holdSeats(req(b, f, 0));   // reclaims the lapsed hold

        bookingService.cancelBooking(ha.id());                         // A's stale booking

        assertEquals("HELD", seatStatus(f, 0), "B's hold must be untouched");
        assertEquals(BookingStatus.CONFIRMED, bookingService.confirmBooking(hb.id()).status());
    }

    // ------------------------------------------------------------------ 7: history

    @Test
    void history_keepsAccurateRecordOfEveryOutcome() {
        User a = newUser("a");
        Fixture f = fixture(3, true);
        BookingResponse confirmed = bookingService.confirmBooking(bookingService.holdSeats(req(a, f, 0)).id());
        BookingResponse cancelled = bookingService.cancelBooking(bookingService.createBooking(req(a, f, 1)).id());
        BookingResponse lapsed = bookingService.holdSeats(req(a, f, 2));
        expireHold(lapsed.id());   // sweeper has NOT run yet

        List<BookingResponse> page = bookingService.getBookingByUser(a.getId(),
                PageRequest.of(0, 10, Sort.by(Sort.Direction.DESC, "bookedAt"))).getContent();

        Map<Long, BookingStatus> byId = page.stream().collect(Collectors.toMap(BookingResponse::id, BookingResponse::status));
        assertEquals(3, byId.size());
        assertEquals(BookingStatus.CONFIRMED, byId.get(confirmed.id()));
        assertEquals(BookingStatus.CANCELLED, byId.get(cancelled.id()));
        assertEquals(BookingStatus.EXPIRED, byId.get(lapsed.id()), "lapsed hold is reported as EXPIRED even before the sweep");
        BookingResponse c = page.stream().filter(r -> r.id().equals(confirmed.id())).findFirst().orElseThrow();
        assertEquals(List.of("A1"), c.seats().stream().map(BookingResponse.SeatInfo::seatNumber).toList());
        assertEquals(0, c.totalPrice().compareTo(new java.math.BigDecimal("250")));
        assertNotNull(c.confirmedAt());
        assertEquals(f.show().getId(), c.showId());
    }

    // ------------------------------------------------------------------ 5: concurrency

    @Test
    void concurrentHolds_sameSeat_exactlyOneWins() throws Exception {
        assertExactlyOneWinner(fixture(1, true), 4);
    }

    @Test
    void concurrentHolds_onShowWithoutInventory_stillExactlyOneWinner() throws Exception {
        // Lazy creation of show_seats must itself be race-free.
        assertExactlyOneWinner(fixture(1, false), 4);
    }

    private void assertExactlyOneWinner(Fixture f, int threads) throws Exception {
        List<User> users = new ArrayList<>();
        for (int i = 0; i < threads; i++) users.add(newUser("u" + i));

        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch ready = new CountDownLatch(threads), go = new CountDownLatch(1);
        AtomicInteger wins = new AtomicInteger(), conflicts = new AtomicInteger();
        List<Throwable> unexpected = Collections.synchronizedList(new ArrayList<>());
        for (User u : users) {
            pool.submit(() -> {
                ready.countDown();
                try {
                    go.await();
                    bookingService.holdSeats(req(u, f, 0));
                    wins.incrementAndGet();
                } catch (SeatAlreadyBookedException e) {
                    conflicts.incrementAndGet();
                } catch (Throwable t) {
                    unexpected.add(t);
                }
                return null;
            });
        }
        ready.await();
        go.countDown();
        pool.shutdown();
        assertTrue(pool.awaitTermination(30, TimeUnit.SECONDS));

        assertTrue(unexpected.isEmpty(), "unexpected failures: " + unexpected);
        assertEquals(1, wins.get(), "exactly one hold must win");
        assertEquals(threads - 1, conflicts.get());
        assertEquals("HELD", seatStatus(f, 0));
        assertEquals(1, jdbc.queryForObject("select count(*) from bookings where show_id=?", Integer.class, f.show().getId()),
                "losers must not leave booking rows behind");
        assertEquals(1, jdbc.queryForObject("select count(*) from show_seats where show_id=?", Integer.class, f.show().getId()));
    }

    // ------------------------------------------------------------------ inventory

    @Test
    void ensureInventory_isIdempotent_andCoversEverySeat() {
        Fixture f = fixture(3, false);
        assertEquals(0, showSeatRepository.findByShowId(f.show().getId()).size());

        showService.ensureInventory(f.show());
        showService.ensureInventory(f.show());

        assertEquals(3, showSeatRepository.findByShowId(f.show().getId()).size());
    }
}
