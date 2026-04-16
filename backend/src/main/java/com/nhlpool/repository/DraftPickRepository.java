package com.nhlpool.repository;

import com.nhlpool.domain.DraftPick;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.util.List;

public interface DraftPickRepository extends JpaRepository<DraftPick, Long> {

    @Query("select dp from DraftPick dp join fetch dp.player join fetch dp.team order by dp.pickNumber asc")
    List<DraftPick> findAllWithPlayerAndTeamOrderByPickNumber();

    List<DraftPick> findByTeamIdOrderByPickNumberAsc(Long teamId);

    long countByTeamId(Long teamId);
}
