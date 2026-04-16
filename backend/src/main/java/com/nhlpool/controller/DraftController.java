package com.nhlpool.controller;

import com.nhlpool.domain.DraftConfig;
import com.nhlpool.domain.DraftPick;
import com.nhlpool.domain.User;
import com.nhlpool.service.DraftService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/draft")
@RequiredArgsConstructor
public class DraftController {

    private final DraftService draftService;

    @GetMapping("/config")
    public ResponseEntity<DraftConfig> getConfig() {
        return ResponseEntity.ok(draftService.getConfig());
    }

    @PutMapping("/config")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<DraftConfig> updateConfig(@RequestBody Map<String, Object> body) {
        Integer playersPerTeam = body.containsKey("playersPerTeam") ? (Integer) body.get("playersPerTeam") : null;
        String draftOrder = body.containsKey("draftOrder") ? (String) body.get("draftOrder") : null;
        return ResponseEntity.ok(draftService.updateConfig(playersPerTeam, draftOrder));
    }

    @PostMapping("/start")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<DraftConfig> startDraft() {
        return ResponseEntity.ok(draftService.startDraft());
    }

    @PostMapping("/pick")
    public ResponseEntity<DraftPick> makePick(@AuthenticationPrincipal User user, @RequestBody Map<String, Long> body) {
        Long teamId = user.getTeam() != null ? user.getTeam().getId() : null;
        if (teamId == null) {
            throw new IllegalStateException("You are not assigned to a team");
        }
        return ResponseEntity.ok(draftService.makePick(teamId, body.get("playerId")));
    }

    @GetMapping("/board")
    public ResponseEntity<List<DraftPick>> getDraftBoard() {
        return ResponseEntity.ok(draftService.getDraftBoard());
    }

    @GetMapping("/order")
    public ResponseEntity<List<DraftService.DraftOrderEntry>> getDraftOrder() {
        return ResponseEntity.ok(draftService.getFullDraftOrder());
    }

    @PostMapping("/reset")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<DraftConfig> resetDraft() {
        return ResponseEntity.ok(draftService.resetDraft());
    }
}
