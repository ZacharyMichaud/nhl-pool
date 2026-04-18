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

@RestController
@RequestMapping("/api/predictions")
@RequiredArgsConstructor
public class PredictionController {

    private final PredictionRepository predictionRepository;
    private final SeriesRepository seriesRepository;
    private final PoolTeamRepository poolTeamRepository;
    private final PoolRoundRepository poolRoundRepository;

    @GetMapping("/round/{roundNumber}")
    public ResponseEntity<List<Prediction>> getPredictionsByRound(@PathVariable Integer roundNumber) {
        return ResponseEntity.ok(predictionRepository.findByRoundNumber(roundNumber));
    }

    @PostMapping
    @Transactional
    public ResponseEntity<Prediction> submitPrediction(
            @AuthenticationPrincipal User user,
            @RequestBody Map<String, Object> body) {

        Long teamId = user.getTeam() != null ? user.getTeam().getId() : null;
        if (teamId == null) {
            throw new IllegalStateException("You are not assigned to a team");
        }

        Long seriesId = ((Number) body.get("seriesId")).longValue();
        String winner = (String) body.get("predictedWinnerAbbrev");
        Integer games = (Integer) body.get("predictedGames");

        Series series = seriesRepository.findByIdWithRound(seriesId)
                .orElseThrow(() -> new IllegalArgumentException("Series not found"));

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

    @GetMapping("/series/round/{roundNumber}")
    public ResponseEntity<List<Series>> getSeriesByRound(@PathVariable Integer roundNumber) {
        return ResponseEntity.ok(seriesRepository.findByRoundNumber(roundNumber));
    }
}
