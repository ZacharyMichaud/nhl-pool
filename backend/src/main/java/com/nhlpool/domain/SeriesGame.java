package com.nhlpool.domain;

import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDate;

/**
 * Caches individual game results within a playoff series.
 * Finished games never change, so they are written once and served from DB.
 * Live games are fetched on-demand and NOT persisted until they finish.
 */
@Entity
@Table(
    name = "series_game",
    uniqueConstraints = @UniqueConstraint(columnNames = {"series_id", "game_number"})
)
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class SeriesGame {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "series_id", nullable = false)
    private Series series;

    @Column(nullable = false)
    private Integer gameNumber;

    @Column(nullable = false)
    private String awayAbbrev;

    @Column(nullable = false)
    private String homeAbbrev;

    @Column(nullable = false)
    private Integer awayScore;

    @Column(nullable = false)
    private Integer homeScore;

    /** Final game state when persisted: OFF or FINAL (live games are not stored). */
    @Column(nullable = false)
    private String gameState;

    /** REG, OT, or SO */
    @Column(nullable = false)
    private String periodType;

    /** Current or final period number. 0 for PRE games. */
    @Builder.Default
    private Integer periodNumber = 0;

    /** Clock remaining for live games (e.g. "14:22"). Empty for PRE/FINAL. */
    @Builder.Default
    private String timeRemaining = "";

    @Column(nullable = false)
    private LocalDate gameDate;
}
