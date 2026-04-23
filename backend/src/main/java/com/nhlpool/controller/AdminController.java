package com.nhlpool.controller;

import com.fasterxml.jackson.databind.JsonNode;
import com.nhlpool.domain.*;
import com.nhlpool.repository.*;
import com.nhlpool.service.*;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/admin")
@PreAuthorize("hasRole('ADMIN')")
@RequiredArgsConstructor
public class AdminController {

    private final PlayerSyncService playerSyncService;
    private final NhlApiService nhlApiService;
    private final ScoringService scoringService;
    private final SeriesSyncService seriesSyncService;
    private final SeriesGameSyncService seriesGameSyncService;
    private final PoolTeamRepository poolTeamRepository;
    private final UserRepository userRepository;
    private final PoolRoundRepository poolRoundRepository;
    private final SeriesRepository seriesRepository;
    private final DraftPickRepository draftPickRepository;
    private final DraftConfigRepository draftConfigRepository;
    private final PlayerRepository playerRepository;

    @PostMapping("/sync/rosters")
    public ResponseEntity<Map<String, String>> syncRosters() {
        playerSyncService.syncAllPlayoffRosters();
        return ResponseEntity.ok(Map.of("message", "Roster sync complete"));
    }

    @PostMapping("/sync/stats")
    public ResponseEntity<Map<String, String>> syncStats() {
        playerSyncService.syncDraftedPlayerStats();
        return ResponseEntity.ok(Map.of("message", "Stats sync complete"));
    }

    @PostMapping("/sync/all-stats")
    public ResponseEntity<Map<String, String>> syncAllStats() {
        playerSyncService.syncAllPlayerStats();
        return ResponseEntity.ok(Map.of("message", "Full stats sync complete"));
    }

    /**
     * Sync stats directly from a game's boxscore — instant, no NHL processing delay.
     * Use this for games that just ended whose stats haven't appeared in game-log yet.
     * Find the game ID on NHL.com: the number in the URL when viewing a game.
     */
    @PostMapping("/sync/boxscore/{gameId}")
    public ResponseEntity<Map<String, String>> syncFromBoxscore(@PathVariable Long gameId) {
        List<Player> drafted = playerRepository.findByDraftedTrue();
        nhlApiService.syncDraftedPlayersFromBoxscore(drafted, gameId);
        playerSyncService.broadcastStatsUpdated();
        return ResponseEntity.ok(Map.of("message", "Boxscore sync complete for game " + gameId));
    }

    @GetMapping("/scoring-rules")
    public ResponseEntity<Map<String, Object>> getScoringRules() {
        return ResponseEntity.ok(Map.of(
                "playerRules", scoringService.getAllScoringRules(),
                "predictionRules", scoringService.getAllPredictionScoringRules()
        ));
    }

    @PutMapping("/scoring-rules/{id}")
    public ResponseEntity<ScoringRule> updateScoringRule(
            @PathVariable Long id, @RequestBody Map<String, Object> body) {
        Integer pointValue = body.containsKey("pointValue") ? (Integer) body.get("pointValue") : null;
        Boolean enabled = body.containsKey("enabled") ? (Boolean) body.get("enabled") : null;
        return ResponseEntity.ok(scoringService.updateScoringRule(id, pointValue, enabled));
    }

    @PutMapping("/prediction-scoring-rules/{id}")
    public ResponseEntity<PredictionScoringRule> updatePredictionScoringRule(
            @PathVariable Long id, @RequestBody Map<String, Object> body) {
        Integer correctWinnerPoints = body.containsKey("correctWinnerPoints") ? (Integer) body.get("correctWinnerPoints") : null;
        Integer correctGamesBonus = body.containsKey("correctGamesBonus") ? (Integer) body.get("correctGamesBonus") : null;
        Integer connSmytheBonus = body.containsKey("connSmytheBonus") ? (Integer) body.get("connSmytheBonus") : null;
        return ResponseEntity.ok(scoringService.updatePredictionScoringRule(id, correctWinnerPoints, correctGamesBonus, connSmytheBonus));
    }

    // Team management
    @PostMapping("/teams")
    public ResponseEntity<PoolTeam> createTeam(@RequestBody Map<String, String> body) {
        PoolTeam team = PoolTeam.builder().name(body.get("name")).build();
        return ResponseEntity.ok(poolTeamRepository.save(team));
    }

    @PutMapping("/teams/{id}/members")
    public ResponseEntity<PoolTeam> assignMembers(@PathVariable Long id, @RequestBody Map<String, List<Long>> body) {
        PoolTeam team = poolTeamRepository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("Team not found"));
        List<Long> userIds = body.get("userIds");
        userIds.forEach(userId -> {
            User user = userRepository.findById(userId)
                    .orElseThrow(() -> new IllegalArgumentException("User not found: " + userId));
            user.setTeam(team);
            userRepository.save(user);
        });
        return ResponseEntity.ok(poolTeamRepository.findById(id).orElseThrow());
    }

    @PatchMapping("/teams/{id}")
    public ResponseEntity<PoolTeam> renameTeam(@PathVariable Long id, @RequestBody Map<String, String> body) {
        PoolTeam team = poolTeamRepository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("Team not found"));
        String newName = body.get("name");
        if (newName == null || newName.isBlank()) {
            return ResponseEntity.badRequest().build();
        }
        team.setName(newName.trim());
        return ResponseEntity.ok(poolTeamRepository.save(team));
    }

    @DeleteMapping("/teams/{id}")
    public ResponseEntity<Void> deleteTeam(@PathVariable Long id) {
        // Unlink any users assigned to this team
        userRepository.findAll().stream()
                .filter(u -> u.getTeam() != null && u.getTeam().getId().equals(id))
                .forEach(u -> { u.setTeam(null); userRepository.save(u); });
        // Remove any draft picks for this team
        draftPickRepository.deleteAll(draftPickRepository.findByTeamIdOrderByPickNumberAsc(id));
        poolTeamRepository.deleteById(id);
        return ResponseEntity.noContent().build();
    }

    // Round management
    @GetMapping("/rounds")
    public ResponseEntity<List<PoolRound>> getRounds() {
        return ResponseEntity.ok(poolRoundRepository.findAll());
    }

    @PutMapping("/rounds/{id}/status")
    public ResponseEntity<PoolRound> updateRoundStatus(@PathVariable Long id, @RequestBody Map<String, String> body) {
        PoolRound round = poolRoundRepository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("Round not found"));
        round.setStatus(RoundStatus.valueOf(body.get("status")));
        return ResponseEntity.ok(poolRoundRepository.save(round));
    }

    // Bracket
    @GetMapping("/bracket")
    public ResponseEntity<JsonNode> getBracket() {
        return ResponseEntity.ok(nhlApiService.getPlayoffBracket());
    }

    // Sync series from NHL API
    @PostMapping("/sync/series")
    public ResponseEntity<Map<String, String>> syncSeries() {
        seriesSyncService.syncSeriesFromApi();
        seriesGameSyncService.syncAllSeriesGames();
        return ResponseEntity.ok(Map.of("message", "Series + game history sync complete"));
    }

    @PostMapping("/sync/series-games")
    public ResponseEntity<Map<String, String>> syncSeriesGames() {
        seriesGameSyncService.syncAllSeriesGames();
        return ResponseEntity.ok(Map.of("message", "Series game cache refresh complete"));
    }

    @GetMapping("/users")
    public ResponseEntity<List<User>> getUsers() {
        return ResponseEntity.ok(userRepository.findAllWithTeams());
    }

    @DeleteMapping("/users/{id}")
    public ResponseEntity<Void> deleteUser(@PathVariable Long id) {
        User user = userRepository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("User not found"));
        user.setTeam(null);
        userRepository.save(user);
        userRepository.deleteById(id);
        return ResponseEntity.noContent().build();
    }

    /** Unlinks every user from their team without deleting teams or picks. */
    @PostMapping("/reset-teams")
    public ResponseEntity<String> resetTeams() {
        userRepository.findAll().forEach(u -> {
            if (u.getTeam() != null) {
                u.setTeam(null);
                userRepository.save(u);
            }
        });
        return ResponseEntity.ok("All team assignments cleared");
    }

    /** Toggle the predictions-locked flag. */
    @PostMapping("/lock/predictions")
    public ResponseEntity<DraftConfig> togglePredictionsLock() {
        DraftConfig cfg = draftConfigRepository.findAll().stream().findFirst()
                .orElseThrow(() -> new IllegalStateException("Draft config not found"));
        cfg.setPredictionsLocked(!Boolean.TRUE.equals(cfg.getPredictionsLocked()));
        return ResponseEntity.ok(draftConfigRepository.save(cfg));
    }

    /** Toggle the Conn Smythe locked flag. */
    @PostMapping("/lock/conn-smythe")
    public ResponseEntity<DraftConfig> toggleConnSmytheLock() {
        DraftConfig cfg = draftConfigRepository.findAll().stream().findFirst()
                .orElseThrow(() -> new IllegalStateException("Draft config not found"));
        cfg.setConnSmytheLocked(!Boolean.TRUE.equals(cfg.getConnSmytheLocked()));
        return ResponseEntity.ok(draftConfigRepository.save(cfg));
    }
}
