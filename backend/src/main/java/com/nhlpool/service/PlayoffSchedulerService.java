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
    private final PlayerSyncService playerSyncService;
    private final PlayerRepository playerRepository;

    /** gameId → startTimeUTC — refreshed daily */
    private final Map<Long, Instant> todaysGames = new ConcurrentHashMap<>();

    /** gameId → last sortOrder seen — resets when todaysGames is refreshed */
    private final Map<Long, Integer> lastSortOrder = new ConcurrentHashMap<>();

    /** gameIds that have already been fully processed (went OFF) */
    private final Set<Long> processedGames = ConcurrentHashMap.newKeySet();

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

        for (Map.Entry<Long, Instant> entry : todaysGames.entrySet()) {
            long gameId = entry.getKey();
            Instant startTime = entry.getValue();

            // GATE: game hasn't started yet — pure in-memory timestamp check, zero API calls
            if (now.isBefore(startTime)) {
                log.debug("[Scheduler] Game {} hasn't started yet (starts {}), skipping", gameId, startTime);
                continue;
            }

            // Already fully processed (game went OFF earlier)
            if (processedGames.contains(gameId)) continue;

            // Fetch current game state
            String gameState = nhlApiService.getGameState(gameId);

            if (gameState.isEmpty()) {
                log.warn("[Scheduler] Could not determine gameState for game {}", gameId);
                continue;
            }

            if ("OFF".equals(gameState) || "FINAL".equals(gameState) || "7".equals(gameState)) {
                // Game just finished — use boxscore for INSTANT stats (game-log lags 2-4h)
                log.info("[Scheduler] Game {} is FINISHED ({}). Running boxscore + series sync.", gameId, gameState);
                try {
                    List<Player> drafted = playerRepository.findByDraftedTrue();
                    // Phase 1: immediate boxscore stats (live, no delay)
                    nhlApiService.syncDraftedPlayersFromBoxscore(drafted, gameId);
                    // Phase 2: series scores
                    seriesSyncService.syncSeriesFromApi();
                    playerSyncService.broadcastStatsUpdated();
                    log.info("[Scheduler] Post-game sync complete for game {}.", gameId);
                } catch (Exception e) {
                    log.error("[Scheduler] Error during post-game sync for game {}", gameId, e);
                }
                processedGames.add(gameId);

            } else if ("LIVE".equals(gameState) || "CRIT".equals(gameState)) {
                // Game is live — check for new goals
                log.debug("[Scheduler] Game {} is LIVE — checking play-by-play", gameId);
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
        List<Long> nhlPlayerIdsToSync = new ArrayList<>();

        for (JsonNode play : plays) {
            int sortOrder = play.path("sortOrder").asInt(0);
            if (sortOrder <= knownSortOrder) continue; // already seen

            maxSortOrder = Math.max(maxSortOrder, sortOrder);

            if ("goal".equals(play.path("typeDescKey").asText(""))) {
                JsonNode details = play.path("details");
                log.info("[Scheduler] New goal detected in game {} (sortOrder={})", gameId, sortOrder);

                long scorerId = details.path("scoringPlayerId").asLong(0);
                long assist1Id = details.path("assist1PlayerId").asLong(0);
                long assist2Id = details.path("assist2PlayerId").asLong(0);

                if (scorerId > 0) nhlPlayerIdsToSync.add(scorerId);
                if (assist1Id > 0) nhlPlayerIdsToSync.add(assist1Id);
                if (assist2Id > 0) nhlPlayerIdsToSync.add(assist2Id);
            }
        }

        // Update the high-water mark regardless
        if (maxSortOrder > knownSortOrder) {
            lastSortOrder.put(gameId, maxSortOrder);
        }

        // Sync players involved in new goals — use boxscore for the LIVE game
        // (game-log won't reflect in-progress goals; boxscore updates in real-time)
        if (!nhlPlayerIdsToSync.isEmpty()) {
            log.info("[Scheduler] New goal(s) in game {} — syncing boxscore for affected drafted players", gameId);
            List<Player> involved = nhlPlayerIdsToSync.stream()
                    .distinct()
                    .map(nhlId -> playerRepository.findByNhlPlayerId(nhlId).orElse(null))
                    .filter(p -> p != null && p.getDrafted())
                    .toList();

            if (!involved.isEmpty()) {
                nhlApiService.syncDraftedPlayersFromBoxscore(involved, gameId);
                playerSyncService.broadcastStatsUpdated();
            } else {
                log.debug("[Scheduler] No drafted players involved in new goal(s) — no sync needed");
            }
        }
    }
}
