package com.nhlpool.controller;

import com.nhlpool.domain.*;
import com.nhlpool.repository.*;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@RestController
@RequestMapping("/api/predictions")
@RequiredArgsConstructor
public class PredictionController {

    private final PredictionRepository predictionRepository;
    private final SeriesRepository seriesRepository;
    private final PoolTeamRepository poolTeamRepository;
    private final PoolRoundRepository poolRoundRepository;
    private final UserRepository userRepository;
    private final com.nhlpool.repository.DraftConfigRepository draftConfigRepository;
    private final com.nhlpool.repository.PredictionScoringRuleRepository predictionScoringRuleRepository;

    @GetMapping("/round/{roundNumber}")
    @Transactional(readOnly = true)
    public ResponseEntity<List<Prediction>> getPredictionsByRound(
            @PathVariable Integer roundNumber,
            @AuthenticationPrincipal User principal) {
        User user = userRepository.findByUsername(principal.getUsername())
                .orElseThrow(() -> new IllegalArgumentException("User not found"));
        Long teamId = user.getTeam() != null ? user.getTeam().getId() : null;
        if (teamId == null) {
            return ResponseEntity.ok(List.of());
        }
        return ResponseEntity.ok(predictionRepository.findByTeamIdAndRoundNumber(teamId, roundNumber));
    }

    @PostMapping
    @Transactional
    public ResponseEntity<Prediction> submitPrediction(
            @AuthenticationPrincipal User principal,
            @RequestBody Map<String, Object> body) {

        User user = userRepository.findByUsername(principal.getUsername())
                .orElseThrow(() -> new IllegalArgumentException("User not found"));
        Long teamId = user.getTeam() != null ? user.getTeam().getId() : null;
        if (teamId == null) {
            throw new IllegalStateException("You are not assigned to a team");
        }

        Long seriesId = ((Number) body.get("seriesId")).longValue();
        String winner = (String) body.get("predictedWinnerAbbrev");
        Integer games = (Integer) body.get("predictedGames");

        Series series = seriesRepository.findByIdWithRound(seriesId)
                .orElseThrow(() -> new IllegalArgumentException("Series not found"));

        // Check if predictions are globally locked by admin
        draftConfigRepository.findAll().stream().findFirst().ifPresent(cfg -> {
            if (Boolean.TRUE.equals(cfg.getPredictionsLocked())) {
                throw new IllegalStateException("Predictions are locked by the admin");
            }
        });

        // Check if round is still accepting predictions (UPCOMING status)
        PoolRound round = series.getRound();
        if (round.getStatus() == RoundStatus.COMPLETED) {
            throw new IllegalStateException("This round is already completed");
        }

        PoolTeam team = poolTeamRepository.findById(teamId)
                .orElseThrow(() -> new IllegalArgumentException("Team not found"));

        Prediction prediction = predictionRepository.findByTeamIdAndSeriesId(teamId, seriesId)
                .orElse(Prediction.builder().team(team).series(series).build());

        prediction.setPredictedWinnerAbbrev(winner);
        prediction.setPredictedGames(games);

        return ResponseEntity.ok(predictionRepository.save(prediction));
    }

    @ExceptionHandler(IllegalStateException.class)
    public ResponseEntity<Map<String, String>> handleBadState(IllegalStateException ex) {
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(Map.of("error", ex.getMessage()));
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<Map<String, String>> handleBadArg(IllegalArgumentException ex) {
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(Map.of("error", ex.getMessage()));
    }

    @GetMapping("/series")
    public ResponseEntity<List<Series>> getAllSeries() {
        return ResponseEntity.ok(seriesRepository.findAllWithRound());
    }

    /**
     * Returns the prediction scoring rules (points per round) for all rounds.
     * Returns a map of roundNumber → { correctWinnerPoints, correctGamesBonus }
     * so the frontend can compute earned points without admin access.
     */
    @GetMapping("/scoring-rules")
    public ResponseEntity<List<PredictionScoringRule>> getPredictionScoringRules() {
        return ResponseEntity.ok(predictionScoringRuleRepository.findAll());
    }

    @GetMapping("/series/round/{roundNumber}")
    public ResponseEntity<List<Series>> getSeriesByRound(@PathVariable Integer roundNumber) {
        return ResponseEntity.ok(seriesRepository.findByRoundNumber(roundNumber));
    }

    /**
     * Returns all teams' predictions for a given round.
     * Only reveals data when predictions are locked by an admin.
     */
    @GetMapping("/all-teams/round/{roundNumber}")
    @Transactional(readOnly = true)
    public ResponseEntity<List<Prediction>> getAllTeamsPredictions(@PathVariable Integer roundNumber) {
        boolean locked = draftConfigRepository.findAll().stream()
                .findFirst()
                .map(cfg -> Boolean.TRUE.equals(cfg.getPredictionsLocked()))
                .orElse(false);

        if (!locked) {
            return ResponseEntity.ok(List.of());
        }

        return ResponseEntity.ok(predictionRepository.findByRoundNumber(roundNumber));
    }
}
