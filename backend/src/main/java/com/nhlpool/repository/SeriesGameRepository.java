package com.nhlpool.repository;

import com.nhlpool.domain.SeriesGame;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface SeriesGameRepository extends JpaRepository<SeriesGame, Long> {

    List<SeriesGame> findBySeriesIdOrderByGameNumberAsc(Long seriesId);

    Optional<SeriesGame> findBySeriesIdAndGameNumber(Long seriesId, Integer gameNumber);

    /** Returns the highest game number stored for a given series (to resume scanning). */
    default int maxGameNumberForSeries(Long seriesId) {
        return findBySeriesIdOrderByGameNumberAsc(seriesId)
                .stream()
                .mapToInt(SeriesGame::getGameNumber)
                .max()
                .orElse(0);
    }
}
