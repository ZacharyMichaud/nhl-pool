package com.nhlpool.domain;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.persistence.*;
import lombok.*;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

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

    /** Safe members summary for JSON — avoids circular reference. */
    @JsonProperty("members")
    public List<Map<String, Object>> getMembersInfo() {
        if (members == null) return List.of();
        return members.stream()
                .map(u -> Map.<String, Object>of("id", u.getId(), "displayName", u.getDisplayName()))
                .collect(Collectors.toList());
    }
}
