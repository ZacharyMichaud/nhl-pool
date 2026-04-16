package com.nhlpool.repository;

import com.nhlpool.domain.DraftConfig;
import org.springframework.data.jpa.repository.JpaRepository;

public interface DraftConfigRepository extends JpaRepository<DraftConfig, Long> {
}
