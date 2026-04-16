package com.nhlpool.controller;

import com.nhlpool.domain.PoolTeam;
import com.nhlpool.domain.User;
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
        PoolTeam team = poolTeamRepository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("Team not found"));
        team.setConnSmythePredictionPlayerId(body.get("playerId"));
        return ResponseEntity.ok(poolTeamRepository.save(team));
    }
}
