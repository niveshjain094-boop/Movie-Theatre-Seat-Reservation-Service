package com.cfs.BMS.repository;

import com.cfs.BMS.entity.Show;
import com.cfs.BMS.entity.Theater;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

public interface ShowRepository extends JpaRepository<Show,Long> {
    
  List<Show> findByMovieId(Long MovieId);
  List<Show> findByScreenId(Long screenId);
  List<Show> findByMovieIdAndShowDate(Long movieId, LocalDate showDate);
  List<Show> findByScreenIdAndShowDate(Long screenId, LocalDate showDate);

  /** Serialises creation of a show's seat inventory. */
  @Lock(LockModeType.PESSIMISTIC_WRITE)
  @Query("SELECT s FROM Show s WHERE s.id = :id")
  Optional<Show> findByIdForUpdate(@Param("id") Long id);


}
