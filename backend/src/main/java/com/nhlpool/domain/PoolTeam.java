package com.nhlpool.domain;

import com.fasterxml.jackson.annotation.JsonIgnore;
import jakarta.persistence.*;
import lombok.*;

import java.util.ArrayList;
import java.util.List;

@Entity
@Table(name = "pool_teams")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class PoolTeam {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private String name;

    @JsonIgnore
    @OneToMany(mappedBy = "team", fetch = FetchType.LAZY)
    @Builder.Default
    private List<User> members = new ArrayList<>();

    @JsonIgnore
    @OneToMany(mappedBy = "team", fetch = FetchType.LAZY)
    @Builder.Default
    private List<DraftPick> draftPicks = new ArrayList<>();

    // Conn Smythe prediction — NHL player ID
    private Long connSmythePredictionPlayerId;
}
