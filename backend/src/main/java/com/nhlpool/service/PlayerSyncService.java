package com.nhlpool.service;

import com.nhlpool.domain.Player;
import com.nhlpool.repository.PlayerRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;
import java.util.Set;

@Service
@RequiredArgsConstructor
@Slf4j
public class PlayerSyncService {

    private final NhlApiService nhlApiService;
    private final PlayerRepository playerRepository;
    private final SimpMessagingTemplate messagingTemplate;

    /**
     * Sync playoff rosters — typically called once at startup or by admin
     */
    public void syncAllPlayoffRosters() {
        Set<String> playoffTeams = nhlApiService.getPlayoffTeamAbbrevs();
        if (playoffTeams.isEmpty()) {
            log.warn("No playoff teams found — playoffs may not have started yet");
            return;
        }
        log.info("Syncing rosters for playoff teams: {}", playoffTeams);
        nhlApiService.syncPlayoffRosters(playoffTeams);
    }

    /**
     * Sync stats for all drafted players.
     *
     * Two-phase approach:
     *   Phase 1 — Game-log (accurate historical totals, but 2-4h delay after game ends)
     *   Phase 2 — Boxscore for today's finished games (instant, fills the gap while game-log catches up)
     *
     * The guard in syncPlayoffStats prevents Phase 1 from overwriting Phase 2 data.
     */
    public void syncDraftedPlayerStats() {
        List<Player> draftedPlayers = playerRepository.findByDraftedTrue();
        if (draftedPlayers.isEmpty()) {
            log.warn("[Sync] No drafted players found — nothing to sync");
            return;
        }

        int[] succeeded = {0}, failed = {0}, changed = {0};

        for (Player player : draftedPlayers) {
            int beforeG  = player.getPlayoffGoals()       != null ? player.getPlayoffGoals()       : 0;
            int beforeA  = player.getPlayoffAssists()     != null ? player.getPlayoffAssists()     : 0;
            int beforeP  = player.getPlayoffPoints()      != null ? player.getPlayoffPoints()      : 0;
            int beforeGP = player.getPlayoffGamesPlayed() != null ? player.getPlayoffGamesPlayed() : 0;
            try {
                nhlApiService.syncPlayoffStats(player);
                int afterG  = player.getPlayoffGoals()       != null ? player.getPlayoffGoals()       : 0;
                int afterA  = player.getPlayoffAssists()     != null ? player.getPlayoffAssists()     : 0;
                int afterP  = player.getPlayoffPoints()      != null ? player.getPlayoffPoints()      : 0;
                int afterGP = player.getPlayoffGamesPlayed() != null ? player.getPlayoffGamesPlayed() : 0;
                boolean wasChanged = afterG != beforeG || afterA != beforeA || afterP != beforeP || afterGP != beforeGP;
                if (wasChanged) {
                    changed[0]++;
                    log.info("[Sync]  ✓ {} ({}) — GP:{}->{} G:{}->{} A:{}->{} PTS:{}->{}",
                            player.getFullName(), player.getTeamAbbrev(),
                            beforeGP, afterGP, beforeG, afterG, beforeA, afterA, beforeP, afterP);
                } else {
                    log.debug("[Sync]  · {} ({}) — no change (GP:{} G:{} A:{} PTS:{})",
                            player.getFullName(), player.getTeamAbbrev(), afterGP, afterG, afterA, afterP);
                }
                succeeded[0]++;
            } catch (Exception e) {
                log.error("[Sync]  ✗ {} ({}) — FAILED: {}", player.getFullName(), player.getTeamAbbrev(), e.getMessage());
                failed[0]++;
            }
        }
        if (failed[0] > 0) {
            log.warn("[Sync] Phase 1 complete: {} ok, {} failed", succeeded[0], failed[0]);
        }

        try {
            Map<Long, String> finishedGames = nhlApiService.getTodaysFinishedPlayoffGames();
            if (!finishedGames.isEmpty()) {
                log.info("[Sync] Running boxscore sync for {} finished game(s)", finishedGames.size());
                for (long gameId : finishedGames.keySet()) {
                    nhlApiService.syncDraftedPlayersFromBoxscore(draftedPlayers, gameId);
                }
            }
        } catch (Exception e) {
            log.error("[Sync] Boxscore phase failed: {}", e.getMessage());
        }

        Set<String> eliminated = nhlApiService.getEliminatedTeamAbbrevs();
        draftedPlayers.forEach(player -> {
            if (eliminated.contains(player.getTeamAbbrev()) && !player.getEliminated()) {
                player.setEliminated(true);
                playerRepository.save(player);
                log.info("[Sync] Marked {} ({}) as ELIMINATED", player.getFullName(), player.getTeamAbbrev());
            }
        });

        if (changed[0] > 0) {
            log.info("[Sync] Done — {} player(s) updated.", changed[0]);
        } else {
            log.debug("[Sync] Done — no stat changes detected.");
        }
        broadcastStatsUpdated();
    }


    /**
     * Sync ALL stats (reg season + playoff + game log splits) — manual, slow
     */
    public void syncAllPlayerStats() {
        List<Player> players = playerRepository.findAll();
        int total = players.size();
        log.info("Syncing ALL stats (incl. game logs) for {} players — this will take a while", total);
        for (int i = 0; i < players.size(); i++) {
            log.info("[{}/{}] Processing player stats...", i + 1, total);
            nhlApiService.syncAllStats(players.get(i));
        }
        log.info("All player stats sync complete ({} players)", total);
    }


    /**
     * Push a WebSocket notification so all connected clients know to refresh standings/players.
     */
    public void broadcastStatsUpdated() {
        try {
            messagingTemplate.convertAndSend("/topic/stats-updated", System.currentTimeMillis());
            log.info("[WS] Broadcast stats-updated to /topic/stats-updated");
        } catch (Exception e) {
            log.warn("[WS] Failed to broadcast stats-updated", e);
        }
    }
}
