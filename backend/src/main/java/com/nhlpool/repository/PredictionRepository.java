package com.nhlpool.repository;

import com.nhlpool.domain.Prediction;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.util.List;
import java.util.Optional;

public interface PredictionRepository extends JpaRepository<Prediction, Long> {

    Optional<Prediction> findByTeamIdAndSeriesId(Long teamId, Long seriesId);

    @Query("select p from Prediction p join fetch p.team join fetch p.series s join fetch s.round where s.round.roundNumber = :roundNumber")
    List<Prediction> findByRoundNumber(Integer roundNumber);

    @Query("select p from Prediction p join fetch p.series s join fetch s.round where p.team.id = :teamId")
    List<Prediction> findByTeamId(Long teamId);
}
