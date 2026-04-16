package com.nhlpool.controller;

import com.nhlpool.service.ScoringService;
import com.nhlpool.service.ScoringService.TeamStanding;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/standings")
@RequiredArgsConstructor
public class StandingsController {

    private final ScoringService scoringService;

    @GetMapping
    public ResponseEntity<List<TeamStanding>> getStandings() {
        return ResponseEntity.ok(scoringService.getStandings());
    }
}
