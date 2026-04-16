package com.nhlpool.repository;

import com.nhlpool.domain.PredictionScoringRule;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface PredictionScoringRuleRepository extends JpaRepository<PredictionScoringRule, Long> {
    Optional<PredictionScoringRule> findByRoundNumber(Integer roundNumber);
}
