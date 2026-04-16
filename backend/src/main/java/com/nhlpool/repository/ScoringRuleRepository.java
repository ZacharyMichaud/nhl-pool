package com.nhlpool.repository;

import com.nhlpool.domain.ScoringRule;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface ScoringRuleRepository extends JpaRepository<ScoringRule, Long> {
    Optional<ScoringRule> findByStatName(String statName);
}
