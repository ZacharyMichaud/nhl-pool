package com.nhlpool.controller;

import com.nhlpool.domain.Player;
import com.nhlpool.domain.PoolTeam;
import com.nhlpool.domain.User;
import com.nhlpool.repository.DraftPickRepository;
import com.nhlpool.repository.PlayerRepository;
import com.nhlpool.repository.PoolTeamRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/teams")
@RequiredArgsConstructor
public class TeamController {

    private final PoolTeamRepository poolTeamRepository;
    private final com.nhlpool.repository.DraftConfigRepository draftConfigRepository;
    private final PlayerRepository playerRepository;
    private final DraftPickRepository draftPickRepository;

    @GetMapping
    public ResponseEntity<List<PoolTeam>> getTeams() {
        return ResponseEntity.ok(poolTeamRepository.findAllWithMembers());
    }

    @GetMapping("/{id}")
    public ResponseEntity<PoolTeam> getTeam(@PathVariable Long id) {
        return ResponseEntity.ok(poolTeamRepository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("Team not found")));
    }

    @PutMapping("/{id}/conn-smythe")
    public ResponseEntity<PoolTeam> setConnSmythe(
            @AuthenticationPrincipal User user,
            @PathVariable Long id,
            @RequestBody Map<String, Long> body) {
        if (user.getTeam() == null || !user.getTeam().getId().equals(id)) {
            throw new IllegalStateException("You can only set Conn Smythe for your own team");
        }
        // Check if Conn Smythe is locked by admin
        draftConfigRepository.findAll().stream().findFirst().ifPresent(cfg -> {
            if (Boolean.TRUE.equals(cfg.getConnSmytheLocked())) {
                throw new IllegalStateException("Conn Smythe predictions are locked by the admin");
            }
        });
        PoolTeam team = poolTeamRepository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("Team not found"));

        Long newPlayerId = body.get("playerId");
        Long oldPlayerId = team.getConnSmythePredictionPlayerId();

        // ── Unmark old CS pick as drafted (only if not a roster pick) ──────────
        if (oldPlayerId != null && !oldPlayerId.equals(newPlayerId)) {
            playerRepository.findById(oldPlayerId).ifPresent(oldPlayer -> {
                boolean stillOnRoster = draftPickRepository.existsByPlayerId(oldPlayerId);
                if (!stillOnRoster) {
                    oldPlayer.setDrafted(false);
                    playerRepository.save(oldPlayer);
                }
            });
        }

        // ── Mark new CS pick as drafted so live sync picks them up ─────────────
        if (newPlayerId != null) {
            playerRepository.findById(newPlayerId).ifPresent(newPlayer -> {
                if (!newPlayer.getDrafted()) {
                    newPlayer.setDrafted(true);
                    playerRepository.save(newPlayer);
                }
            });
        }

        team.setConnSmythePredictionPlayerId(newPlayerId);
        return ResponseEntity.ok(poolTeamRepository.save(team));
    }
}
