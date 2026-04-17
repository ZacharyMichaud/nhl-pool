package com.nhlpool.controller;

import com.nhlpool.domain.Player;
import com.nhlpool.repository.PlayerRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/players")
@RequiredArgsConstructor
public class PlayerController {

    private final PlayerRepository playerRepository;

    @GetMapping("/available")
    public ResponseEntity<List<Player>> getAvailablePlayers(
            @RequestParam(required = false) String teamAbbrev,
            @RequestParam(required = false) String position) {
        List<Player> players;
        if (teamAbbrev != null && !teamAbbrev.isBlank()) {
            players = playerRepository.findByDraftedFalseAndEliminatedFalseAndTeamAbbrev(teamAbbrev);
        } else {
            players = playerRepository.findByDraftedFalseAndEliminatedFalse();
        }
        // Always exclude goalies
        players = players.stream()
                .filter(p -> !"G".equals(p.getPosition()))
                .toList();
        if (position != null && !position.isBlank()) {
            players = players.stream()
                    .filter(p -> p.getPosition().equals(position))
                    .toList();
        }
        return ResponseEntity.ok(players);
    }

    @GetMapping("/search")
    public ResponseEntity<List<Player>> searchPlayers(@RequestParam String q) {
        return ResponseEntity.ok(
                playerRepository.findByFirstNameContainingIgnoreCaseOrLastNameContainingIgnoreCase(q, q));
    }

    @GetMapping
    public ResponseEntity<List<Player>> getAllPlayers() {
        return ResponseEntity.ok(playerRepository.findAll());
    }
}
