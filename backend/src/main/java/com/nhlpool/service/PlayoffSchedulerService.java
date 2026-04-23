package com.nhlpool.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.nhlpool.domain.Player;
import com.nhlpool.repository.PlayerRepository;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Watches live NHL playoff games and triggers automatic syncs:
 * - On a new goal: syncs playoff stats for the scorer + assisters only (fast)
 * - On game end (gameState → OFF): runs a full series sync + full player stat sync
 *
 * Schedule awareness: the daily schedule refresh stores each game's startTimeUTC.
 * The live watcher checks Instant.now() >= startTimeUTC before making any API call,
 * so there are zero network calls before puck drop or outside game days.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class PlayoffSchedulerService {

    private final NhlApiService nhlApiService;
    private final SeriesSyncService seriesSyncService;
    private final SeriesGameSyncService seriesGameSyncService;
    private final PlayerSyncService playerSyncService;
    private final PlayerRepository playerRepository;

    /** gameId → startTimeUTC — refreshed daily */
    private final Map<Long, Instant> todaysGames = new ConcurrentHashMap<>();

    /** gameId → last sortOrder seen — resets when todaysGames is refreshed */
    private final Map<Long, Integer> lastSortOrder = new ConcurrentHashMap<>();

    /** gameIds that have already been fully processed (went OFF) */
    private final Set<Long> processedGames = ConcurrentHashMap.newKeySet();

    /**
     * Tracks how many post-game boxscore syncs we've done per game.
     * We run 3 passes (at t=0, t+30s, t+60s) before marking a game processed,
     * giving the NHL API time to finalize OT goal stats and series results.
     */
    private final Map<Long, Integer> postGameSyncCount = new ConcurrentHashMap<>();
    private static final int POST_GAME_SYNCS = 3;

    /**
     * Returns the set of NHL team abbreviations that are actively playing in a live
     * playoff game right now (gameState LIVE or CRIT).
     * Uses the cached todaysGames schedule; falls back to on-demand API call if empty.
     */
    public Set<String> getLiveTeamAbbrevs() {
        Set<String> liveAbbrevs = new HashSet<>();
        try {
            Map<Long, Instant> games = todaysGames.isEmpty()
                    ? nhlApiService.getTodaysPlayoffGames()
                    : todaysGames;

            Instant now = Instant.now();
            for (Map.Entry<Long, Instant> entry : games.entrySet()) {
                long gameId = entry.getKey();
                if (now.isBefore(entry.getValue())) continue; // not started yet
                if (processedGames.contains(gameId)) continue; // already finished
                String state = nhlApiService.getGameState(gameId);
                if ("LIVE".equals(state) || "CRIT".equals(state)) {
                    // Fetch team abbrevs from the schedule
                    Set<String> teams = nhlApiService.getTeamAbbrevsForGame(gameId);
                    liveAbbrevs.addAll(teams);
                }
            }
        } catch (Exception e) {
            log.warn("[LiveGames] Failed to determine live teams: {}", e.getMessage());
        }
        return liveAbbrevs;
    }

    // -------------------------------------------------------------------------
    // Job 1: Daily schedule refresh
    // -------------------------------------------------------------------------

    /**
     * Runs at startup and at 10:00 AM UTC every day.
     * Fetches today's playoff game IDs + start times and resets tracking state.
     */
    @PostConstruct
    @Scheduled(cron = "0 0 * * * *") // every hour (on the hour), UTC
    public void refreshTodaysSchedule() {
        log.debug("[Scheduler] Refreshing today's playoff schedule...");
        Map<Long, Instant> games = nhlApiService.getTodaysPlayoffGames();

        todaysGames.clear();
        todaysGames.putAll(games);
        games.keySet().forEach(id -> lastSortOrder.putIfAbsent(id, 0));
        processedGames.removeIf(id -> !games.containsKey(id));

        if (games.isEmpty()) {
            log.debug("[Scheduler] No playoff games today — live watcher will be a no-op.");
        } else {
            log.debug("[Scheduler] Tracking {} game(s) today: {}", games.size(), games.keySet());
            catchUpFinishedGames(games);
        }

        // Keep the game cache fresh (populates on first boot, catches any missed games)
        try {
            seriesGameSyncService.syncAllSeriesGames();
        } catch (Exception e) {
            log.warn("[Scheduler] series-game sync during schedule refresh failed: {}", e.getMessage());
        }
    }

    /**
     * Called at startup / schedule refresh. Immediately syncs any games that are already
     * in a finished state so we don't wait 30s for the live watcher to pick them up.
     */
    private void catchUpFinishedGames(Map<Long, Instant> games) {
        for (long gameId : games.keySet()) {
            if (processedGames.contains(gameId)) continue;
            String state = nhlApiService.getGameState(gameId);
            if ("OFF".equals(state) || "FINAL".equals(state) || "7".equals(state)) {
                log.info("[Scheduler] Catch-up: game {} already finished ({}). Running boxscore + series sync.", gameId, state);
                processedGames.add(gameId);
                try {
                    List<Player> drafted = playerRepository.findByDraftedTrue();
                    nhlApiService.syncDraftedPlayersFromBoxscore(drafted, gameId);
                    seriesSyncService.syncSeriesFromApi();
                    seriesGameSyncService.syncAllSeriesGames();
                    playerSyncService.broadcastStatsUpdated();
                    log.info("[Scheduler] Catch-up sync complete for game {}.", gameId);
                } catch (Exception e) {
                    log.error("[Scheduler] Error during catch-up sync for game {}", gameId, e);
                }
            }
        }
    }

    // -------------------------------------------------------------------------
    // Job 2: Live game watcher
    // -------------------------------------------------------------------------

    /**
     * Runs every 30 seconds.
     *
     * GATE: If now < startTimeUTC for ALL remaining games → returns immediately (zero API calls).
     * Once a game's window is open, fetches play-by-play and reacts to new goal events.
     */
    @Scheduled(fixedDelay = 30_000, initialDelay = 60_000)
    public void watchLiveGames() {
        if (todaysGames.isEmpty()) return;

        Instant now = Instant.now();

        // Fast gate: if no unprocessed game has started yet, nothing to do — zero API calls
        boolean anyActive = todaysGames.entrySet().stream()
                .anyMatch(e -> !processedGames.contains(e.getKey()) && !now.isBefore(e.getValue()));
        if (!anyActive) return;

        // Update all live game rows in series_game with one schedule API call
        boolean anyLive = todaysGames.entrySet().stream()
                .anyMatch(e -> !processedGames.contains(e.getKey()) && !now.isBefore(e.getValue()));
        if (anyLive) {
            try { seriesGameSyncService.updateAllLiveGames(); } catch (Exception ignored) {}
        }

        for (Map.Entry<Long, Instant> entry : todaysGames.entrySet()) {
            long gameId    = entry.getKey();
            Instant startTime = entry.getValue();

            if (now.isBefore(startTime)) continue; // not started yet
            if (processedGames.contains(gameId)) continue; // already finished

            // Fetch current game state
            String gameState = nhlApiService.getGameState(gameId);

            if (gameState.isEmpty()) {
                log.warn("[Scheduler] Could not determine gameState for game {}", gameId);
                continue;
            }

            if ("OFF".equals(gameState) || "FINAL".equals(gameState) || "7".equals(gameState)) {
                int pass = postGameSyncCount.getOrDefault(gameId, 0);

                if (pass < POST_GAME_SYNCS) {
                    postGameSyncCount.put(gameId, pass + 1);
                    if (pass == 0) {
                        log.info("[Scheduler] Game {} is FINISHED ({}). Running post-game sync (pass 1/{}).",
                                gameId, gameState, POST_GAME_SYNCS);
                    } else {
                        log.info("[Scheduler] Game {} — follow-up sync (pass {}/{}) to catch API-finalized stats.",
                                gameId, pass + 1, POST_GAME_SYNCS);
                    }
                    try {
                        List<Player> drafted = playerRepository.findByDraftedTrue();
                        nhlApiService.syncDraftedPlayersFromBoxscore(drafted, gameId);
                        seriesSyncService.syncSeriesFromApi();
                        seriesGameSyncService.syncAllSeriesGames();
                        playerSyncService.broadcastStatsUpdated();
                    } catch (Exception e) {
                        log.error("[Scheduler] Error during post-game sync (pass {}) for game {}", pass + 1, gameId, e);
                    }
                } else {
                    log.info("[Scheduler] Game {} — all {} post-game syncs done, marking complete.", gameId, POST_GAME_SYNCS);
                    processedGames.add(gameId);
                }

            } else if ("LIVE".equals(gameState) || "CRIT".equals(gameState)) {
                // DB already updated above via updateAllLiveGames() — just check for new goals
                checkForNewGoals(gameId);

            } else {
                // FUT or unknown — game hasn't started per API yet despite our time gate
                log.debug("[Scheduler] Game {} state={} — waiting for live state", gameId, gameState);
            }
        }
    }

    /**
     * Fetches play-by-play for a live game, finds goal events newer than the last
     * seen sortOrder, and triggers a stat sync for the scorer + assisters.
     */
    private void checkForNewGoals(long gameId) {
        List<JsonNode> plays = nhlApiService.getPlayByPlay(gameId);
        if (plays.isEmpty()) return;

        int knownSortOrder = lastSortOrder.getOrDefault(gameId, 0);
        int maxSortOrder = knownSortOrder;
        boolean newGoalDetected = false;

        for (JsonNode play : plays) {
            int sortOrder = play.path("sortOrder").asInt(0);
            if (sortOrder <= knownSortOrder) continue;
            maxSortOrder = Math.max(maxSortOrder, sortOrder);

            if ("goal".equals(play.path("typeDescKey").asText(""))) {
                log.info("[Scheduler] New goal detected in game {} (sortOrder={})", gameId, sortOrder);
                newGoalDetected = true;
            }
        }

        if (maxSortOrder > knownSortOrder) {
            lastSortOrder.put(gameId, maxSortOrder);
        }

        // On any new goal, sync ALL drafted players from the boxscore.
        // We don't rely on scoringPlayerId/assist1PlayerId etc. because those
        // field names and IDs can differ from the boxscore's playerId field.
        // syncDraftedPlayersFromBoxscore skips players not in this game automatically.
        if (newGoalDetected) {
            List<Player> drafted = playerRepository.findByDraftedTrue();
            if (!drafted.isEmpty()) {
                log.info("[Scheduler] Goal in game {} — running boxscore sync ({} players)", gameId, drafted.size());
                nhlApiService.syncDraftedPlayersFromBoxscore(drafted, gameId);
                playerSyncService.broadcastStatsUpdated();
            }
        }
    }

}
