package com.nhlpool.domain;

import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDate;

@Entity
@Table(name = "series")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Series {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "round_id", nullable = false)
    private PoolRound round;

    @Column(nullable = false)
    private String seriesCode; // A, B, C, ... from NHL API

    @Column(nullable = false)
    private String topSeedAbbrev;

    @Column(nullable = false)
    private String bottomSeedAbbrev;

    @Builder.Default
    private Integer topSeedWins = 0;

    @Builder.Default
    private Integer bottomSeedWins = 0;

    private String topSeedLogoUrl;

    private String bottomSeedLogoUrl;

    // Null until series is over
    private String winnerAbbrev;

    // Next scheduled game info (null when series is over or not yet known)
    private LocalDate nextGameDate;
    private String   nextGameHomeAbbrev;
    private String   nextGameAwayAbbrev;

    public boolean isCompleted() {
        return winnerAbbrev != null;
    }

    public int getTotalGames() {
        return topSeedWins + bottomSeedWins;
    }
}
