package com.nhlpool.domain;

import jakarta.persistence.*;
import lombok.*;

@Entity
@Table(name = "pool_rounds")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class PoolRound {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, unique = true)
    private Integer roundNumber; // 1-4

    @Column(nullable = false)
    private String label; // "1st Round", "2nd Round", "Conference Finals", "Stanley Cup Final"

    @Enumerated(EnumType.STRING)
    @Builder.Default
    private RoundStatus status = RoundStatus.UPCOMING;
}
