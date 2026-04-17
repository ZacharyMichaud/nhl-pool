package com.nhlpool.repository;

import com.nhlpool.domain.WatchlistEntry;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface WatchlistRepository extends JpaRepository<WatchlistEntry, Long> {

    @Query("SELECT w FROM WatchlistEntry w JOIN FETCH w.player WHERE w.team.id = :teamId ORDER BY w.rank ASC")
    List<WatchlistEntry> findByTeamIdOrderByRankAsc(@Param("teamId") Long teamId);

    Optional<WatchlistEntry> findByTeamIdAndPlayerId(Long teamId, Long playerId);

    @Modifying
    @Query("DELETE FROM WatchlistEntry w WHERE w.team.id = :teamId AND w.player.id = :playerId")
    void deleteByTeamIdAndPlayerId(@Param("teamId") Long teamId, @Param("playerId") Long playerId);

    boolean existsByTeamIdAndPlayerId(Long teamId, Long playerId);

    @Modifying
    @Query("DELETE FROM WatchlistEntry w WHERE w.player.id = :playerId")
    void deleteByPlayerId(@Param("playerId") Long playerId);
}
