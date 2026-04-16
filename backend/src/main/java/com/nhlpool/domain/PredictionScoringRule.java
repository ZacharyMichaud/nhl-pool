package com.nhlpool.domain;

import jakarta.persistence.*;
import lombok.*;

@Entity
@Table(name = "prediction_scoring_rules")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class PredictionScoringRule {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, unique = true)
    private Integer roundNumber; // 1-4

    @Column(nullable = false)
    private Integer correctWinnerPoints; // 7, 10, 15, 20

    @Column(nullable = false)
    @Builder.Default
    private Integer correctGamesBonus = 3;

    // Only used for "global" row (roundNumber = 0 for Conn Smythe)
    @Builder.Default
    private Integer connSmytheBonus = 0;
}
