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
        log.info("[Scheduler] Refreshing today's playoff schedule...");
        Map<Long, Instant> games = nhlApiService.getTodaysPlayoffGames();

        todaysGames.clear();
        todaysGames.putAll(games);
        // Remove tracking state only for NEW games (preserve progress on games still running)
        games.keySet().forEach(id -> {
            lastSortOrder.putIfAbsent(id, 0);
        });
        processedGames.removeIf(id -> !games.containsKey(id));

        if (games.isEmpty()) {
            log.info("[Scheduler] No playoff games today — live watcher will be a no-op.");
        } else {
            log.info("[Scheduler] Tracking {} game(s) today: {}", games.size(), games.keySet());
            // Catch-up: if any games are already finished (server restarted after game ended), sync now
            catchUpFinishedGames(games);
        }
    }

    /**
     * Called at startup / schedule refresh. Immediately syncs any games that are already
     * in a finished state so we don't wait 30s for the live watcher to pick them up.
     */
    private void catchUpFinishedGames(Map<Long, Instant> games) {
        boolean triggeredSync = false;
        for (long gameId : games.keySet()) {
            if (processedGames.contains(gameId)) continue;
            String state = nhlApiService.getGameState(gameId);
            if ("OFF".equals(state) || "FINAL".equals(state) || "7".equals(state)) {
                log.info("[Scheduler] Catch-up: game {} already finished ({}). Triggering sync.", gameId, state);
                processedGames.add(gameId);
                triggeredSync = true;
            }
        }
        if (triggeredSync) {
            try {
                seriesSyncService.syncSeriesFromApi();
                playerSyncService.syncDraftedPlayerStats();
                log.info("[Scheduler] Catch-up sync complete.");
            } catch (Exception e) {
                log.error("[Scheduler] Error during catch-up sync", e);
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
                // Game just finished — do the full syncs
                log.info("[Scheduler] Game {} is FINISHED ({}). Running full series + player stats sync.", gameId, gameState);
                try {
                    seriesSyncService.syncSeriesFromApi();
                    playerSyncService.syncDraftedPlayerStats();
                    log.info("[Scheduler] Full sync complete after game {} ended.", gameId);
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

        // Sync only the players involved in new goals
        if (!nhlPlayerIdsToSync.isEmpty()) {
            log.info("[Scheduler] Syncing stats for {} player(s) due to new goal event(s) in game {}",
                    nhlPlayerIdsToSync.size(), gameId);
            nhlPlayerIdsToSync.stream()
                    .distinct()
                    .forEach(nhlId -> playerRepository.findByNhlPlayerId(nhlId).ifPresent(player -> {
                        if (player.getDrafted()) {
                            nhlApiService.syncPlayoffStats(player);
                            log.info("[Scheduler] Synced stats for drafted player: {} ({})",
                                    player.getFullName(), player.getTeamAbbrev());
                        }
                    }));
            // Notify all connected clients
            playerSyncService.broadcastStatsUpdated();
        }
    }
}
