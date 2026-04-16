package com.nhlpool.domain;

import jakarta.persistence.*;
import lombok.*;

@Entity
@Table(name = "predictions", uniqueConstraints = {
    @UniqueConstraint(columnNames = {"team_id", "series_id"})
})
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Prediction {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "team_id", nullable = false)
    private PoolTeam team;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "series_id", nullable = false)
    private Series series;

    @Column(nullable = false)
    private String predictedWinnerAbbrev;

    @Column(nullable = false)
    private Integer predictedGames; // 4, 5, 6, or 7
}
