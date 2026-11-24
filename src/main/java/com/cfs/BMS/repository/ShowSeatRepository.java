package com.cfs.BMS.repository;

import com.cfs.BMS.entity.ShowSeat;
import com.cfs.BMS.enums.SeatHoldStatus;
import jakarta.persistence.LockModeType;
import jakarta.persistence.QueryHint;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.jpa.repository.QueryHints;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;

public interface ShowSeatRepository extends JpaRepository<ShowSeat, Long> {

  List<ShowSeat> findByShowId(Long showId);

  /** Read-only listing with seats fetched (no lock). */
  @EntityGraph(attributePaths = "seat")
  @Query("SELECT ss FROM ShowSeat ss WHERE ss.show.id = :showId ORDER BY ss.seat.id")
  List<ShowSeat> findWithSeatByShowId(@Param("showId") Long showId);

  @Query("SELECT ss.seat.id FROM ShowSeat ss WHERE ss.show.id = :showId")
  List<Long> findSeatIdsByShowId(@Param("showId") Long showId);

  /** Rows are always locked in seat-id order so concurrent multi-seat requests cannot deadlock each other. */
  @Lock(LockModeType.PESSIMISTIC_WRITE)
  @Query("SELECT ss FROM ShowSeat ss WHERE ss.show.id = :showId AND ss.seat.id IN :seatIds ORDER BY ss.seat.id")
  List<ShowSeat> lockByShowIdAndSeatIds(@Param("showId") Long showId, @Param("seatIds") List<Long> seatIds);

  @Lock(LockModeType.PESSIMISTIC_WRITE)
  @Query("SELECT ss FROM ShowSeat ss WHERE ss.show.id = :showId ORDER BY ss.seat.id")
  List<ShowSeat> lockByShowId(@Param("showId") Long showId);

  /** All seats currently owned (held or booked) by a booking, locked. */
  @Lock(LockModeType.PESSIMISTIC_WRITE)
  @Query("SELECT ss FROM ShowSeat ss WHERE ss.booking.id = :bookingId ORDER BY ss.seat.id")
  List<ShowSeat> lockByBookingId(@Param("bookingId") Long bookingId);

  /**
   * Expired holds, locked with SKIP LOCKED so the sweeper never waits on (or deadlocks with)
   * a user request that is touching the same rows; skipped rows are picked up on the next run.
   */
  @Lock(LockModeType.PESSIMISTIC_WRITE)
  @QueryHints(@QueryHint(name = "jakarta.persistence.lock.timeout", value = "-2"))
  @Query("SELECT ss FROM ShowSeat ss WHERE ss.status = :status AND ss.holdExpiresAt <= :now ORDER BY ss.id")
  List<ShowSeat> lockExpiredHolds(@Param("status") SeatHoldStatus status, @Param("now") Instant now, Pageable page);
}
