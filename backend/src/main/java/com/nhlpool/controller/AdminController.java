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
    private final PoolTeamRepository poolTeamRepository;
    private final UserRepository userRepository;
    private final PoolRoundRepository poolRoundRepository;
    private final SeriesRepository seriesRepository;
    private final DraftPickRepository draftPickRepository;

    @PostMapping("/sync/rosters")
    public ResponseEntity<String> syncRosters() {
        playerSyncService.syncAllPlayoffRosters();
        return ResponseEntity.ok("Roster sync initiated");
    }

    @PostMapping("/sync/stats")
    public ResponseEntity<String> syncStats() {
        playerSyncService.syncDraftedPlayerStats();
        return ResponseEntity.ok("Stats sync initiated");
    }

    @PostMapping("/sync/all-stats")
    public ResponseEntity<String> syncAllStats() {
        playerSyncService.syncAllPlayerStats();
        return ResponseEntity.ok("Full stats sync initiated");
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
    public ResponseEntity<String> syncSeries() {
        JsonNode bracket = nhlApiService.getPlayoffBracket();
        if (bracket != null && bracket.has("rounds")) {
            bracket.get("rounds").forEach(roundNode -> {
                int roundNumber = roundNode.get("roundNumber").asInt();
                PoolRound poolRound = poolRoundRepository.findByRoundNumber(roundNumber).orElse(null);
                if (poolRound == null) return;

                roundNode.get("series").forEach(seriesNode -> {
                    String seriesCode = seriesNode.get("seriesLetter").asText();
                    String topAbbrev = seriesNode.path("topSeed").path("abbrev").asText();
                    String bottomAbbrev = seriesNode.path("bottomSeed").path("abbrev").asText();
                    int topWins = seriesNode.path("topSeed").path("wins").asInt(0);
                    int bottomWins = seriesNode.path("bottomSeed").path("wins").asInt(0);
                    String topLogo = seriesNode.path("topSeed").path("darkLogo").asText("");
                    String bottomLogo = seriesNode.path("bottomSeed").path("darkLogo").asText("");

                    // Find or create series
                    List<Series> existing = seriesRepository.findByRoundNumber(roundNumber);
                    Series series = existing.stream()
                            .filter(s -> s.getSeriesCode().equals(seriesCode))
                            .findFirst()
                            .orElse(Series.builder()
                                    .round(poolRound)
                                    .seriesCode(seriesCode)
                                    .topSeedAbbrev(topAbbrev)
                                    .bottomSeedAbbrev(bottomAbbrev)
                                    .build());

                    series.setTopSeedWins(topWins);
                    series.setBottomSeedWins(bottomWins);
                    series.setTopSeedLogoUrl(topLogo);
                    series.setBottomSeedLogoUrl(bottomLogo);

                    // Set winner if series is done
                    if (seriesNode.has("winningTeamId") && seriesNode.get("winningTeamId").asInt(0) > 0) {
                        int winningId = seriesNode.get("winningTeamId").asInt();
                        int topId = seriesNode.path("topSeed").path("id").asInt();
                        series.setWinnerAbbrev(winningId == topId ? topAbbrev : bottomAbbrev);
                    }

                    seriesRepository.save(series);
                });
            });
        }
        return ResponseEntity.ok("Series synced from NHL API");
    }

    @GetMapping("/users")
    public ResponseEntity<List<User>> getUsers() {
        return ResponseEntity.ok(userRepository.findAllWithTeams());
    }
}
