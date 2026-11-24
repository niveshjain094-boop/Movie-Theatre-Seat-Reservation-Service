package com.cfs.BMS.repository;

import com.cfs.BMS.entity.Booking;
import com.cfs.BMS.enums.BookingStatus;
import jakarta.persistence.LockModeType;
import jakarta.persistence.QueryHint;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.jpa.repository.QueryHints;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface BookingRepository extends JpaRepository<Booking, Long> {

  Page<Booking> findByUserId(Long userId, Pageable pageable);

  List<Booking> findByShowId(Long showId);

  Optional<Booking> findByIdempotencyKey(UUID idempotencyKey);

  @Lock(LockModeType.PESSIMISTIC_WRITE)
  @Query("SELECT b FROM Booking b WHERE b.id = :id")
  Optional<Booking> findByIdForUpdate(@Param("id") Long id);

  /** Pending bookings whose hold lapsed; SKIP LOCKED for the same reason as ShowSeatRepository.lockExpiredHolds. */
  @Lock(LockModeType.PESSIMISTIC_WRITE)
  @QueryHints(@QueryHint(name = "jakarta.persistence.lock.timeout", value = "-2"))
  @Query("SELECT b FROM Booking b WHERE b.status = :status AND b.holdExpiresAt <= :now ORDER BY b.id")
  List<Booking> lockExpiredHolds(@Param("status") BookingStatus status, @Param("now") Instant now, Pageable page);

  @Query("SELECT s.id FROM Booking b JOIN b.seats s WHERE b.show.id=:showId AND b.status IN :statuses")
  List<Long> findBookedSeatIdsByShowId(@Param("showId") Long showId,
                                       @Param("statuses") List<BookingStatus> statuses);

  default List<Long> findConfirmedSeatIdsByShowId(Long showId) {
    return findBookedSeatIdsByShowId(showId, List.of(BookingStatus.CONFIRMED, BookingStatus.PENDING));
  }
}
