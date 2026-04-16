package com.nhlpool.repository;

import com.nhlpool.domain.PoolRound;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface PoolRoundRepository extends JpaRepository<PoolRound, Long> {
    Optional<PoolRound> findByRoundNumber(Integer roundNumber);
}
