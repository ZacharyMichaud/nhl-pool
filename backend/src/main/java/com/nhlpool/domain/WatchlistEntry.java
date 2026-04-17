package com.nhlpool.domain;

import jakarta.persistence.*;
import lombok.*;

@Entity
@Table(
    name = "watchlist_entries",
    uniqueConstraints = @UniqueConstraint(columnNames = {"team_id", "player_id"})
)
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class WatchlistEntry {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "team_id", nullable = false)
    private PoolTeam team;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "player_id", nullable = false)
    private Player player;

    /** 0-based ordering rank within the team's watchlist */
    @Column(nullable = false)
    private int rank;
}
