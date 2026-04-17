package com.nhlpool.domain;

import jakarta.persistence.*;
import lombok.*;

@Entity
@Table(name = "players")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Player {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(unique = true, nullable = false)
    private Long nhlPlayerId;

    @Column(nullable = false)
    private String firstName;

    @Column(nullable = false)
    private String lastName;

    @Column(nullable = false)
    private String position; // C, L, R, D, G

    @Column(nullable = false)
    private String teamAbbrev; // TOR, MTL, etc.

    private String headshotUrl;

    // Regular season stats — full season
    @Builder.Default
    private Integer regularSeasonGoals = 0;
    @Builder.Default
    private Integer regularSeasonAssists = 0;
    @Builder.Default
    private Integer regularSeasonPoints = 0;
    @Builder.Default
    private Integer regularSeasonGamesPlayed = 0;
    @Builder.Default
    private Integer regularSeasonPowerPlayGoals = 0;
    @Builder.Default
    private Integer regularSeasonPowerPlayPoints = 0;
    private String regularSeasonAvgToi;

    // Regular season stats — last 41 games
    @Builder.Default
    private Integer last41GamesPlayed = 0;
    @Builder.Default
    private Integer last41Goals = 0;
    @Builder.Default
    private Integer last41Assists = 0;
    @Builder.Default
    private Integer last41Points = 0;
    @Builder.Default
    private Integer last41PowerPlayGoals = 0;
    @Builder.Default
    private Integer last41PowerPlayPoints = 0;
    @Column(name = "last41_avg_toi")
    private String last41AvgToi;

    // Regular season stats — last 20 games
    @Builder.Default
    private Integer last20GamesPlayed = 0;
    @Builder.Default
    private Integer last20Goals = 0;
    @Builder.Default
    private Integer last20Assists = 0;
    @Builder.Default
    private Integer last20Points = 0;
    @Builder.Default
    private Integer last20PowerPlayGoals = 0;
    @Builder.Default
    private Integer last20PowerPlayPoints = 0;
    @Column(name = "last20_avg_toi")
    private String last20AvgToi;

    // Playoff stats (real-time synced)
    @Builder.Default
    private Integer playoffGoals = 0;
    @Builder.Default
    private Integer playoffAssists = 0;
    @Builder.Default
    private Integer playoffPoints = 0;
    @Builder.Default
    private Integer playoffGamesPlayed = 0;
    @Builder.Default
    private Integer playoffPowerPlayGoals = 0;
    @Builder.Default
    private Integer playoffPowerPlayPoints = 0;
    private String playoffAvgToi;

    @Builder.Default
    private Boolean eliminated = false;

    @Builder.Default
    private Boolean drafted = false;

    public String getFullName() {
        return firstName + " " + lastName;
    }
}
