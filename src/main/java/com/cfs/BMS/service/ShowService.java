package com.cfs.BMS.service;


import com.cfs.BMS.dto.ShowRequest;
import com.cfs.BMS.entity.Movie;
import com.cfs.BMS.entity.Screen;
import com.cfs.BMS.entity.Seat;
import com.cfs.BMS.entity.Show;
import com.cfs.BMS.entity.ShowSeat;
import com.cfs.BMS.enums.SeatHoldStatus;
import com.cfs.BMS.exception.NotFoundException;
import com.cfs.BMS.repository.SeatRepository;
import com.cfs.BMS.repository.ShowRepository;
import com.cfs.BMS.repository.ShowSeatRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

@Service
@RequiredArgsConstructor
public class ShowService {

    private final ShowRepository showRepository;
    private final MovieService movieService;
    private final ScreenService screenService;
    private final SeatRepository seatRepository;
    private final ShowSeatRepository showSeatRepository;

    //addshow
    @Transactional
    public Show addShow(ShowRequest request)
    {
        Movie movie =movieService.getMovieById(request.getMovieId());
        Screen screen=screenService.getScreenById(request.getScreenId());
        Show show=Show.builder()
                .movie(movie)
                .screen(screen)
                .showDate(request.getShowDate())
                .startTime(request.getStartTime())
                .endTime(request.getEndTime())
                .ticketPrice(request.getTicketPrice())
                .build();

        Show saved = showRepository.save(show);
        ensureInventory(saved);
        return saved;
    }

    /**
     * Makes sure every seat of the show's screen has a show_seats row (status AVAILABLE).
     * Takes a row lock on the show so concurrent callers cannot insert the same rows twice;
     * the unique (show_id, seat_id) constraint remains the final guard.
     */
    @Transactional
    public void ensureInventory(Show show) {
        showRepository.findByIdForUpdate(show.getId());
        Set<Long> existing = new HashSet<>(showSeatRepository.findSeatIdsByShowId(show.getId()));
        List<ShowSeat> missing = seatRepository.findByScreenId(show.getScreen().getId()).stream()
                .filter(seat -> !existing.contains(seat.getId()))
                .map((Seat seat) -> ShowSeat.builder()
                        .show(show).seat(seat).status(SeatHoldStatus.AVAILABLE).build())
                .toList();
        if (!missing.isEmpty()) showSeatRepository.saveAll(missing);
    }

    public List<Show> getAllShow()
    {
        return showRepository.findAll();
    }
    public Show getShowById(Long id)
    {
        return showRepository.findById(id)
                .orElseThrow(()->new NotFoundException("Show not found with id: "+id));

    }

    public List<Show> getShowByMovie(Long movieId)
    {
        return showRepository.findByMovieId(movieId);
    }

    public List<Show> getShowByMovieAndDate(Long movieId, LocalDate date)
    {
        return showRepository.findByMovieIdAndShowDate(movieId,date);
    }

    //getShowByScreen

}
