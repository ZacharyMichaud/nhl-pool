package com.nhlpool.domain;

import jakarta.persistence.*;
import lombok.*;

@Entity
@Table(name = "scoring_rules")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class ScoringRule {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, unique = true)
    private String statName; // GOAL, ASSIST, GWG, SHUTOUT, OT_GOAL

    @Column(nullable = false)
    private Integer pointValue;

    @Builder.Default
    private Boolean enabled = true;
}
