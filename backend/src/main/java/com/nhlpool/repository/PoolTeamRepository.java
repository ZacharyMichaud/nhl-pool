package com.nhlpool.repository;

import com.nhlpool.domain.PoolTeam;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.util.List;

public interface PoolTeamRepository extends JpaRepository<PoolTeam, Long> {

    @Query("select t from PoolTeam t left join fetch t.members")
    List<PoolTeam> findAllWithMembers();
}
