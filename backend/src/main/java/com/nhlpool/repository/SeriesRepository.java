package com.nhlpool.repository;

import com.nhlpool.domain.Series;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface SeriesRepository extends JpaRepository<Series, Long> {

    @Query("select s from Series s join fetch s.round where s.round.roundNumber = :roundNumber")
    List<Series> findByRoundNumber(Integer roundNumber);

    @Query("select s from Series s join fetch s.round order by s.round.roundNumber, s.seriesCode")
    List<Series> findAllWithRound();

    @Query("select s from Series s join fetch s.round where s.id = :id")
    Optional<Series> findByIdWithRound(@Param("id") Long id);
}
