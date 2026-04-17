package com.nhlpool.controller;

import com.nhlpool.domain.Player;
import com.nhlpool.domain.PoolTeam;
import com.nhlpool.domain.User;
import com.nhlpool.domain.WatchlistEntry;
import com.nhlpool.repository.PlayerRepository;
import com.nhlpool.repository.PoolTeamRepository;
import com.nhlpool.repository.WatchlistRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/draft/watchlist")
@RequiredArgsConstructor
public class WatchlistController {

    private final WatchlistRepository watchlistRepository;
    private final PlayerRepository playerRepository;
    private final PoolTeamRepository poolTeamRepository;

    /** GET /api/draft/watchlist — returns ordered watchlist for the caller's team */
    @GetMapping
    public ResponseEntity<List<WatchlistEntryDto>> getWatchlist(@AuthenticationPrincipal User user) {
        Long teamId = resolveTeamId(user);
        List<WatchlistEntry> entries = watchlistRepository.findByTeamIdOrderByRankAsc(teamId);
        List<WatchlistEntryDto> dtos = entries.stream()
                .map(e -> new WatchlistEntryDto(e.getRank(), PlayerDto.from(e.getPlayer())))
                .toList();
        return ResponseEntity.ok(dtos);
    }

    /** POST /api/draft/watchlist/{playerId} — add a player to the team's watchlist */
    @PostMapping("/{playerId}")
    @Transactional
    public ResponseEntity<Void> addPlayer(
            @AuthenticationPrincipal User user,
            @PathVariable Long playerId
    ) {
        Long teamId = resolveTeamId(user);
        if (watchlistRepository.existsByTeamIdAndPlayerId(teamId, playerId)) {
            return ResponseEntity.ok().build(); // idempotent
        }
        Player player = playerRepository.findById(playerId)
                .orElseThrow(() -> new IllegalArgumentException("Player not found"));
        PoolTeam team = poolTeamRepository.findById(teamId)
                .orElseThrow(() -> new IllegalStateException("Team not found"));

        int nextRank = watchlistRepository.findByTeamIdOrderByRankAsc(teamId).size();
        WatchlistEntry entry = WatchlistEntry.builder()
                .team(team)
                .player(player)
                .rank(nextRank)
                .build();
        watchlistRepository.save(entry);
        return ResponseEntity.ok().build();
    }

    /** DELETE /api/draft/watchlist/{playerId} — remove a player from the team's watchlist */
    @DeleteMapping("/{playerId}")
    @Transactional
    public ResponseEntity<Void> removePlayer(
            @AuthenticationPrincipal User user,
            @PathVariable Long playerId
    ) {
        Long teamId = resolveTeamId(user);
        watchlistRepository.deleteByTeamIdAndPlayerId(teamId, playerId);
        // Compact ranks so there are no gaps
        recompactRanks(teamId);
        return ResponseEntity.ok().build();
    }

    /**
     * PUT /api/draft/watchlist/reorder
     * Body: { "playerIds": [3, 1, 7, ...] }  — full ordered list of player IDs
     */
    @PutMapping("/reorder")
    @Transactional
    public ResponseEntity<Void> reorder(
            @AuthenticationPrincipal User user,
            @RequestBody Map<String, List<Long>> body
    ) {
        Long teamId = resolveTeamId(user);
        List<Long> playerIds = body.get("playerIds");
        if (playerIds == null) return ResponseEntity.badRequest().build();

        List<WatchlistEntry> entries = watchlistRepository.findByTeamIdOrderByRankAsc(teamId);
        Map<Long, WatchlistEntry> byPlayerId = new java.util.HashMap<>();
        entries.forEach(e -> byPlayerId.put(e.getPlayer().getId(), e));

        for (int i = 0; i < playerIds.size(); i++) {
            WatchlistEntry entry = byPlayerId.get(playerIds.get(i));
            if (entry != null) {
                entry.setRank(i);
                watchlistRepository.save(entry);
            }
        }
        return ResponseEntity.ok().build();
    }

    // ── Helpers ──────────────────────────────────────────────────────────────

    private Long resolveTeamId(User user) {
        PoolTeam team = user.getTeam();
        if (team == null) {
            throw new IllegalStateException("You are not assigned to a team");
        }
        return team.getId();
    }

    private void recompactRanks(Long teamId) {
        List<WatchlistEntry> remaining = watchlistRepository.findByTeamIdOrderByRankAsc(teamId);
        for (int i = 0; i < remaining.size(); i++) {
            remaining.get(i).setRank(i);
        }
        watchlistRepository.saveAll(remaining);
    }

    // ── DTOs ─────────────────────────────────────────────────────────────────

    public record WatchlistEntryDto(int rank, PlayerDto player) {}

    public record PlayerDto(
            Long id,
            String firstName,
            String lastName,
            String position,
            String teamAbbrev,
            String headshotUrl,
            Boolean drafted
    ) {
        public static PlayerDto from(Player p) {
            return new PlayerDto(
                    p.getId(),
                    p.getFirstName(),
                    p.getLastName(),
                    p.getPosition(),
                    p.getTeamAbbrev(),
                    p.getHeadshotUrl(),
                    p.getDrafted()
            );
        }
    }
}
