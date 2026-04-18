package com.nhlpool.domain;

import jakarta.persistence.*;
import lombok.*;

@Entity
@Table(name = "draft_config")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class DraftConfig {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Builder.Default
    private Integer playersPerTeam = 10;

    // Comma-separated team IDs for draft order, e.g. "1,3,5,2,4"
    @Column(length = 500)
    private String draftOrder;

    @Enumerated(EnumType.STRING)
    @Builder.Default
    private DraftStatus status = DraftStatus.NOT_STARTED;

    @Builder.Default
    private Integer currentPickNumber = 0;

    @Builder.Default
    private Integer totalTeams = 5;

    @Builder.Default
    private Boolean predictionsLocked = false;

    @Builder.Default
    private Boolean connSmytheLocked = false;
}
