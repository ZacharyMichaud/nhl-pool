package com.nhlpool.repository;

import com.nhlpool.domain.Player;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface PlayerRepository extends JpaRepository<Player, Long> {
    Optional<Player> findByNhlPlayerId(Long nhlPlayerId);
    List<Player> findByDraftedFalseAndEliminatedFalse();
    List<Player> findByDraftedFalseAndEliminatedFalseAndTeamAbbrev(String teamAbbrev);
    List<Player> findByDraftedTrue();
    List<Player> findByTeamAbbrevIn(List<String> teamAbbrevs);
    List<Player> findByFirstNameContainingIgnoreCaseOrLastNameContainingIgnoreCase(String firstName, String lastName);
    boolean existsByNhlPlayerId(Long nhlPlayerId);
}
