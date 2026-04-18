package com.nhlpool.repository;

import com.nhlpool.domain.DraftPick;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;

public interface DraftPickRepository extends JpaRepository<DraftPick, Long> {

    @Query("select dp from DraftPick dp join fetch dp.player join fetch dp.team order by dp.pickNumber asc")
    List<DraftPick> findAllWithPlayerAndTeamOrderByPickNumber();

    /** Plain derived query — used by AdminController for deletions (no fetch needed). */
    List<DraftPick> findByTeamIdOrderByPickNumberAsc(Long teamId);

    /** Eagerly fetches player + team to avoid LazyInitializationException in ScoringService. */
    @Query("select dp from DraftPick dp join fetch dp.player join fetch dp.team where dp.team.id = :teamId order by dp.pickNumber asc")
    List<DraftPick> findByTeamIdWithPlayerOrderByPickNumberAsc(@Param("teamId") Long teamId);

    long countByTeamId(Long teamId);
}
