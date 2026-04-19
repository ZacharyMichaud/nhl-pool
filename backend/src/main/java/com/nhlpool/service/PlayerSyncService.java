package com.nhlpool.service;

import com.nhlpool.domain.Player;
import com.nhlpool.repository.PlayerRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Set;

@Service
@RequiredArgsConstructor
@Slf4j
public class PlayerSyncService {

    private final NhlApiService nhlApiService;
    private final PlayerRepository playerRepository;

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
     * Called by PlayoffSchedulerService after a game ends (and optionally after each goal).
     */
    public void syncDraftedPlayerStats() {
        List<Player> draftedPlayers = playerRepository.findByDraftedTrue();
        if (draftedPlayers.isEmpty()) return;

        log.info("Syncing playoff stats for {} drafted players", draftedPlayers.size());
        draftedPlayers.forEach(nhlApiService::syncPlayoffStats);

        // Update eliminated status
        Set<String> eliminated = nhlApiService.getEliminatedTeamAbbrevs();
        draftedPlayers.forEach(player -> {
            if (eliminated.contains(player.getTeamAbbrev()) && !player.getEliminated()) {
                player.setEliminated(true);
                playerRepository.save(player);
                log.info("Marked {} as eliminated", player.getFullName());
            }
        });
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
     * Sync ONLY drafted player playoff stats — manual, fast
     */
    public void syncDraftedPlayoffStats() {
        List<Player> draftedPlayers = playerRepository.findByDraftedTrue();
        if (draftedPlayers.isEmpty()) return;
        log.info("Syncing playoff stats for {} drafted players", draftedPlayers.size());
        draftedPlayers.forEach(nhlApiService::syncPlayoffStats);
    }
}
