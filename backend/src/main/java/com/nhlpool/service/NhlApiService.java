package com.nhlpool.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.nhlpool.domain.Player;
import com.nhlpool.repository.PlayerRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.*;
import java.util.stream.Collectors;


@Service
@RequiredArgsConstructor
@Slf4j
public class NhlApiService {

    private final WebClient nhlApiClient;
    private final PlayerRepository playerRepository;

    @Value("${app.nhl.season}")
    private String season;


    /**
     * Fetches the playoff bracket/series carousel and returns the raw JSON
     */
    public JsonNode getPlayoffBracket() {
        try {
            return nhlApiClient.get()
                    .uri("/playoff-series/carousel/{season}/", season)
                    .retrieve()
                    .bodyToMono(JsonNode.class)
                    .block();
        } catch (Exception e) {
            log.error("Failed to fetch playoff bracket", e);
            return null;
        }
    }

    /**
     * Returns today's and yesterday's playoff games mapped as gameId → startTimeUTC.
     * Used by the live scheduler to know which games to watch.
     */
    public Map<Long, Instant> getTodaysPlayoffGames() {
        ZoneId eastern = ZoneId.of("America/New_York");
        LocalDate todayEt     = LocalDate.now(eastern);
        LocalDate yesterdayEt = todayEt.minusDays(1);

        Map<Long, Instant> result = new LinkedHashMap<>();
        for (LocalDate date : new LocalDate[]{todayEt, yesterdayEt}) {
            result.putAll(fetchScheduleForDate(date).startTimes);
        }

        log.debug("[Schedule] Found {} playoff game(s) for ET dates [{}, {}]: {}",
                result.size(), yesterdayEt, todayEt, result.keySet());
        return result;
    }

    /**
     * Returns only today's/yesterday's finished playoff games (gameState OFF or FINAL).
     * Reads gameState directly from the schedule response — NO extra per-game API calls.
     * Used by Phase 2 of the manual sync to avoid 20+ individual getGameState() calls.
     */
    public Map<Long, String> getTodaysFinishedPlayoffGames() {
        ZoneId eastern = ZoneId.of("America/New_York");
        LocalDate todayEt     = LocalDate.now(eastern);
        LocalDate yesterdayEt = todayEt.minusDays(1);

        Map<Long, String> finished = new LinkedHashMap<>();
        for (LocalDate date : new LocalDate[]{todayEt, yesterdayEt}) {
            ScheduleResult sr = fetchScheduleForDate(date);
            sr.gameStates.forEach((gameId, state) -> {
                if ("OFF".equals(state) || "FINAL".equals(state) || "7".equals(state)) {
                    finished.put(gameId, state);
                }
            });
        }

        log.debug("[Schedule] Found {} finished playoff game(s) for ET dates [{}, {}]: {}",
                finished.size(), yesterdayEt, todayEt, finished.keySet());
        return finished;
    }

    /** Internal holder for schedule fetch results. */
    private record ScheduleResult(Map<Long, Instant> startTimes, Map<Long, String> gameStates) {}

    /**
     * Fetches all playoff games for a specific ET date from /schedule/{date}.
     * Captures both startTimeUTC and gameState in one HTTP call.
     */
    private ScheduleResult fetchScheduleForDate(LocalDate etDate) {
        try {
            JsonNode schedule = nhlApiClient.get()
                    .uri("/schedule/{date}", etDate.toString())
                    .retrieve()
                    .bodyToMono(JsonNode.class)
                    .block();

            if (schedule == null || !schedule.has("gameWeek")) return new ScheduleResult(Map.of(), Map.of());

            Map<Long, Instant> startTimes  = new LinkedHashMap<>();
            Map<Long, String>  gameStates  = new LinkedHashMap<>();

            schedule.get("gameWeek").forEach(dayNode -> {
                // /schedule/{date} returns the full game week — filter to this specific date only
                if (!etDate.toString().equals(dayNode.path("date").asText(""))) return;
                dayNode.path("games").forEach(game -> {
                    if (game.path("gameType").asInt(0) != 3) return; // playoffs only
                    long   gameId    = game.path("id").asLong();
                    String startUtc  = game.path("startTimeUTC").asText("");
                    String gameState = game.path("gameState").asText("");
                    if (gameId > 0 && !startUtc.isEmpty()) {
                        startTimes.put(gameId, Instant.parse(startUtc));
                        gameStates.put(gameId, gameState);
                    }
                });
            });
            return new ScheduleResult(startTimes, gameStates);
        } catch (Exception e) {
            log.warn("[Schedule] Failed to fetch schedule for ET date {}: {}", etDate, e.getMessage());
            return new ScheduleResult(Map.of(), Map.of());
        }
    }

    /**
     * Returns all play events for a game (sorted ascending by sortOrder).
     * Returns an empty list on error or if no plays exist yet.
     */
    public List<JsonNode> getPlayByPlay(long gameId) {
        try {
            JsonNode root = nhlApiClient.get()
                    .uri("/gamecenter/{gameId}/play-by-play", gameId)
                    .retrieve()
                    .bodyToMono(JsonNode.class)
                    .block();

            if (root == null || !root.has("plays")) return List.of();

            List<JsonNode> plays = new ArrayList<>();
            root.get("plays").forEach(plays::add);
            plays.sort(Comparator.comparingInt(p -> p.path("sortOrder").asInt(0)));
            return plays;
        } catch (Exception e) {
            log.warn("Failed to fetch play-by-play for game {}: {}", gameId, e.getMessage());
            return List.of();
        }
    }

    /**
     * Syncs stats for all drafted players from a specific game's boxscore.
     *
     * WHY: /game-log endpoint takes 2-4h after a game ends to reflect stats.
     *      /gamecenter/{gameId}/boxscore is live — final stats appear immediately
     *      when the game ends. This is used by the post-game scheduler trigger
     *      to give instant updates, while the game-log sync handles the full
     *      historical totals once the NHL processes the game.
     *
     * Strategy: fetch game-log totals for all previous games EXCLUDING this one,
     * then add this game's boxscore stats on top.
     */
    public void syncDraftedPlayersFromBoxscore(List<Player> draftedPlayers, long gameId) {
        log.debug("[Boxscore] Fetching boxscore for game {} to get immediate post-game stats", gameId);
        try {
            JsonNode boxscore = nhlApiClient.get()
                    .uri("/gamecenter/{gameId}/boxscore", gameId)
                    .retrieve()
                    .bodyToMono(JsonNode.class)
                    .block();

            if (boxscore == null) {
                log.warn("[Boxscore] Null response for game {}", gameId);
                return;
            }

            // Build a lookup map: nhlPlayerId → stats node from boxscore
            Map<Long, JsonNode> boxscoreStats = new HashMap<>();
            JsonNode playerStats = boxscore.path("playerByGameStats");
            for (String side : new String[]{"homeTeam", "awayTeam"}) {
                JsonNode team = playerStats.path(side);
                for (String group : new String[]{"forwards", "defense", "goalies"}) {
                    team.path(group).forEach(p -> {
                        long pid = p.path("playerId").asLong(0);
                        if (pid > 0) boxscoreStats.put(pid, p);
                    });
                }
            }

            log.debug("[Boxscore] Game {} boxscore has {} player entries", gameId, boxscoreStats.size());

            for (Player player : draftedPlayers) {
                JsonNode stats = boxscoreStats.get(player.getNhlPlayerId());
                if (stats == null) {
                    log.debug("[Boxscore] {} not in boxscore for game {} (not playing in this game)", player.getFullName(), gameId);
                    continue;
                }

                // Fetch game-log totals BEFORE this game to use as a base
                // (we re-sum all finalized games and add this game's boxscore on top)
                int baseGP = 0, baseG = 0, baseA = 0, basePts = 0, basePPG = 0, basePPP = 0;
                long baseToi = 0;
                try {
                    JsonNode gameLog = nhlApiClient.get()
                            .uri("/player/{playerId}/game-log/{season}/3", player.getNhlPlayerId(), season)
                            .retrieve()
                            .bodyToMono(JsonNode.class)
                            .block();
                    if (gameLog != null && gameLog.path("gameLog").isArray()) {
                        for (JsonNode g : gameLog.path("gameLog")) {
                            // Skip this game if it's already in the log (avoid double-count)
                            if (g.path("gameId").asLong(0) == gameId) continue;
                            baseGP++;
                            baseG   += g.path("goals").asInt(0);
                            baseA   += g.path("assists").asInt(0);
                            basePts += g.path("points").asInt(0);
                            basePPG += g.path("powerPlayGoals").asInt(0);
                            basePPP += g.path("powerPlayPoints").asInt(0);
                            String toi = g.path("toi").asText("");
                            if (!toi.isEmpty()) baseToi += toiToSeconds(toi);
                        }
                    }
                } catch (Exception e) {
                    log.warn("[Boxscore] Could not fetch game-log base for {}: {}", player.getFullName(), e.getMessage());
                    // Fall back to current stored values as base
                    baseGP  = player.getPlayoffGamesPlayed() != null ? player.getPlayoffGamesPlayed() : 0;
                    baseG   = player.getPlayoffGoals()      != null ? player.getPlayoffGoals()      : 0;
                    baseA   = player.getPlayoffAssists()    != null ? player.getPlayoffAssists()    : 0;
                    basePts = player.getPlayoffPoints()     != null ? player.getPlayoffPoints()     : 0;
                    basePPG = player.getPlayoffPowerPlayGoals()   != null ? player.getPlayoffPowerPlayGoals()   : 0;
                    basePPP = player.getPlayoffPowerPlayPoints()  != null ? player.getPlayoffPowerPlayPoints()  : 0;
                }

                // Add this game's boxscore stats
                int g   = stats.path("goals").asInt(0);
                int a   = stats.path("assists").asInt(0);
                int pts = stats.path("points").asInt(0);
                int ppg = stats.path("powerPlayGoals").asInt(0);
                int ppp = stats.path("powerPlayPoints").asInt(0);
                String toiStr = stats.path("toi").asText("");
                long thisToi = toiToSeconds(toiStr);

                int totalGP  = baseGP + 1;
                int totalG   = baseG + g;
                int totalA   = baseA + a;
                int totalPts = basePts + pts;
                int totalPPG = basePPG + ppg;
                int totalPPP = basePPP + ppp;
                long totalToi = baseToi + thisToi;
                String avgToi = totalGP > 0 ? secondsToToi(totalToi / totalGP) : null;

                player.setPlayoffGamesPlayed(totalGP);
                player.setPlayoffGoals(totalG);
                player.setPlayoffAssists(totalA);
                player.setPlayoffPoints(totalPts);
                player.setPlayoffPowerPlayGoals(totalPPG);
                player.setPlayoffPowerPlayPoints(totalPPP);
                player.setPlayoffAvgToi(avgToi);
                playerRepository.save(player);

                log.info("[Boxscore] {} ({}) — game {} stats: G:{} A:{} PTS:{} | season totals now: GP:{} G:{} A:{} PTS:{}",
                        player.getFullName(), player.getTeamAbbrev(), gameId,
                        g, a, pts, totalGP, totalG, totalA, totalPts);
            }
        } catch (Exception e) {
            log.error("[Boxscore] Failed to sync from boxscore for game {}: {}", gameId, e.getMessage());
        }
    }



    /**
     * Returns the current gameState for a specific game ID.
     * First checks /schedule/now (for live/upcoming games in the current week).
     * If the game is not found there (e.g. it already finished and dropped off the schedule),
     * falls back to /gamecenter/{gameId}/landing to get the definitive state.
     * States: "FUT" (upcoming), "LIVE"/"CRIT" (in progress), "OFF"/"FINAL" (finished).
     * Returns "" only if both calls fail.
     */
    public String getGameState(long gameId) {
        // 1. Try /schedule/now first (cheap, covers live games)
        try {
            JsonNode schedule = nhlApiClient.get()
                    .uri("/schedule/now")
                    .retrieve()
                    .bodyToMono(JsonNode.class)
                    .block();

            if (schedule != null) {
                for (JsonNode dayNode : schedule.path("gameWeek")) {
                    for (JsonNode game : dayNode.path("games")) {
                        if (game.path("id").asLong() == gameId) {
                            return game.path("gameState").asText("");
                        }
                    }
                }
            }
        } catch (Exception e) {
            log.warn("Failed to fetch gameState from schedule for game {}: {}", gameId, e.getMessage());
        }

        // 2. Game not in /schedule/now — fall back to gamecenter landing (handles finished games)
        log.debug("[GameState] Game {} not found in /schedule/now — falling back to gamecenter landing", gameId);
        try {
            JsonNode landing = nhlApiClient.get()
                    .uri("/gamecenter/{gameId}/landing", gameId)
                    .retrieve()
                    .bodyToMono(JsonNode.class)
                    .block();

            if (landing != null) {
                String state = landing.path("gameState").asText("");
                if (!state.isEmpty()) {
                    log.info("[GameState] Game {} resolved via gamecenter landing: {}", gameId, state);
                    return state;
                }
            }
        } catch (Exception e) {
            log.warn("Failed to fetch gameState from gamecenter landing for game {}: {}", gameId, e.getMessage());
        }

        return "";
    }

    /**
     * Get all team abbreviations that are in the playoffs based on the bracket data
     */
    public Set<String> getPlayoffTeamAbbrevs() {
        JsonNode bracket = getPlayoffBracket();
        Set<String> teams = new HashSet<>();
        if (bracket != null && bracket.has("rounds")) {
            // Get teams from round 1 (all 16 playoff teams)
            JsonNode rounds = bracket.get("rounds");
            rounds.forEach(round -> {
                if (round.has("series")) {
                    round.get("series").forEach(series -> {
                        if (series.has("topSeed") && series.get("topSeed").has("abbrev")) {
                            teams.add(series.get("topSeed").get("abbrev").asText());
                        }
                        if (series.has("bottomSeed") && series.get("bottomSeed").has("abbrev")) {
                            teams.add(series.get("bottomSeed").get("abbrev").asText());
                        }
                    });
                }
            });
        }
        return teams;
    }

    /**
     * Get eliminated team abbreviations from the bracket
     */
    public Set<String> getEliminatedTeamAbbrevs() {
        JsonNode bracket = getPlayoffBracket();
        Set<String> eliminated = new HashSet<>();
        if (bracket != null && bracket.has("rounds")) {
            bracket.get("rounds").forEach(round -> {
                if (round.has("series")) {
                    round.get("series").forEach(series -> {
                        if (series.has("losingTeamId") && series.has("winningTeamId")) {
                            // Find the loser abbreviation
                            String topAbbrev = series.path("topSeed").path("abbrev").asText("");
                            String bottomAbbrev = series.path("bottomSeed").path("abbrev").asText("");
                            int topId = series.path("topSeed").path("id").asInt();
                            int losingId = series.path("losingTeamId").asInt(0);
                            if (losingId > 0) {
                                eliminated.add(topId == losingId ? topAbbrev : bottomAbbrev);
                            }
                        }
                    });
                }
            });
        }
        return eliminated;
    }

    /**
     * Sync all roster players for a set of teams into the database
     */
    public void syncPlayoffRosters(Set<String> teamAbbrevs) {
        log.info("Starting roster sync for {} teams: {}", teamAbbrevs.size(), teamAbbrevs);
        int[] teamCount = {0};
        teamAbbrevs.forEach(abbrev -> {
            teamCount[0]++;
            log.info("[{}/{}] Fetching roster for {}", teamCount[0], teamAbbrevs.size(), abbrev);
            try {
                JsonNode roster = nhlApiClient.get()
                        .uri("/roster/{abbrev}/{season}", abbrev, season)
                        .retrieve()
                        .bodyToMono(JsonNode.class)
                        .block();

                if (roster != null) {
                    processRosterGroup(roster, "forwards", abbrev);
                    processRosterGroup(roster, "defensemen", abbrev);
                    log.info("|__ {} roster synced (F:{} D:{})",
                            abbrev,
                            roster.path("forwards").size(),
                            roster.path("defensemen").size());
                } else {
                    log.warn("|__ {} returned null roster", abbrev);
                }
            } catch (Exception e) {
                log.error("Failed to sync roster for {}", abbrev, e);
            }
        });
        log.info("Roster sync complete. Total players in DB: {}", playerRepository.count());
    }

    private void processRosterGroup(JsonNode roster, String group, String teamAbbrev) {
        if (!roster.has(group)) return;
        int[] added = {0}, skipped = {0};
        roster.get(group).forEach(playerNode -> {
            long nhlId = playerNode.get("id").asLong();
            if (!playerRepository.existsByNhlPlayerId(nhlId)) {
                Player player = Player.builder()
                        .nhlPlayerId(nhlId)
                        .firstName(playerNode.path("firstName").path("default").asText(""))
                        .lastName(playerNode.path("lastName").path("default").asText(""))
                        .position(playerNode.path("positionCode").asText(""))
                        .teamAbbrev(teamAbbrev)
                        .headshotUrl(playerNode.path("headshot").asText(""))
                        .build();
                playerRepository.save(player);
                log.debug("  Added player: {} {} ({})",
                        playerNode.path("firstName").path("default").asText(),
                        playerNode.path("lastName").path("default").asText(), group);
                added[0]++;
            } else {
                skipped[0]++;
            }
        });
        log.info("  {} {}: +{} added, {} already existed", teamAbbrev, group, added[0], skipped[0]);
    }

    /**
     * Sync ONLY playoff stats for the current season — fast, used by the scheduler.
     *
     * WHY game-log instead of seasonTotals:
     *   The /player/landing seasonTotals array only adds a gameTypeId=3 entry AFTER
     *   the entire playoffs are over. During live playoffs the data lives in the
     *   game-log endpoint: /player/{id}/game-log/{season}/3
     *   We sum each game's stats to produce cumulative totals.
     */
    public void syncPlayoffStats(Player player) {
        try {
            JsonNode gameLogResponse = nhlApiClient.get()
                    .uri("/player/{playerId}/game-log/{season}/3", player.getNhlPlayerId(), season)
                    .retrieve()
                    .bodyToMono(JsonNode.class)
                    .block();

            if (gameLogResponse == null) {
                log.warn("[API] {} — playoff game-log response was null", player.getFullName());
                return;
            }

            JsonNode games = gameLogResponse.path("gameLog");
            if (!games.isArray()) {
                log.warn("[API] {} ({}) — no playoff gameLog array in response (player may not have played yet)",
                        player.getFullName(), player.getTeamAbbrev());
                playerRepository.save(player);
                return;
            }

            // Sum stats across all playoff games
            int gp = 0, goals = 0, assists = 0, points = 0, ppg = 0, ppp = 0;
            long totalToiSeconds = 0;

            for (JsonNode game : games) {
                gp++;
                goals   += game.path("goals").asInt(0);
                assists += game.path("assists").asInt(0);
                points  += game.path("points").asInt(0);
                ppg     += game.path("powerPlayGoals").asInt(0);
                ppp     += game.path("powerPlayPoints").asInt(0);
                String toi = game.path("toi").asText("");
                if (!toi.isEmpty()) totalToiSeconds += toiToSeconds(toi);
            }

            // Guard: never overwrite with data that represents fewer games than we already have.
            // This protects boxscore-synced stats from being clobbered by the game-log endpoint
            // which takes 2-4h to reflect a finished game.
            int storedGP = player.getPlayoffGamesPlayed() != null ? player.getPlayoffGamesPlayed() : 0;
            if (gp < storedGP) {
                log.warn("[API] {} ({}) — game-log returned {} games but DB has {}. " +
                         "Game-log hasn't caught up yet — keeping existing stats to avoid overwrite.",
                        player.getFullName(), player.getTeamAbbrev(), gp, storedGP);
                return; // don't touch the DB — boxscore data is more current
            }

            String avgToi = gp > 0 ? secondsToToi(totalToiSeconds / gp) : null;

            player.setPlayoffGamesPlayed(gp);
            player.setPlayoffGoals(goals);
            player.setPlayoffAssists(assists);
            player.setPlayoffPoints(points);
            player.setPlayoffPowerPlayGoals(ppg);
            player.setPlayoffPowerPlayPoints(ppp);
            player.setPlayoffAvgToi(avgToi);

            log.debug("[API] {} ({}) — playoff game-log: {} games, G:{} A:{} PTS:{} PPG:{} PPP:{} TOI/GP:{}",
                    player.getFullName(), player.getTeamAbbrev(),
                    gp, goals, assists, points, ppg, ppp, avgToi != null ? avgToi : "N/A");

            playerRepository.save(player);
        } catch (Exception e) {
            log.error("[API] Failed to sync playoff stats for {} — {}", player.getFullName(), e.getMessage());
        }
    }

    /**
     * Sync ALL stats (regular season + playoff + game log splits) — slow, manual only
     */
    public void syncAllStats(Player player) {
        log.info("Syncing all stats for {} ({})", player.getFullName(), player.getTeamAbbrev());
        try {
            JsonNode landing = nhlApiClient.get()
                    .uri("/player/{playerId}/landing", player.getNhlPlayerId())
                    .retrieve()
                    .bodyToMono(JsonNode.class)
                    .block();

            if (landing == null) {
                log.warn("  No landing data returned for {}", player.getFullName());
                return;
            }

            boolean foundRegular = false, foundPlayoff = false;
            JsonNode seasonTotals = landing.path("seasonTotals");
            if (seasonTotals.isArray()) {
                for (JsonNode seasonEntry : seasonTotals) {
                    String league = seasonEntry.path("leagueAbbrev").asText("");
                    int gameType = seasonEntry.path("gameTypeId").asInt(0);
                    String seasonStr = String.valueOf(seasonEntry.path("season").asInt(0));

                    if ("NHL".equals(league) && seasonStr.equals(season)) {
                        if (gameType == 2) {
                            player.setRegularSeasonGoals(seasonEntry.path("goals").asInt(0));
                            player.setRegularSeasonAssists(seasonEntry.path("assists").asInt(0));
                            player.setRegularSeasonPoints(seasonEntry.path("points").asInt(0));
                            player.setRegularSeasonGamesPlayed(seasonEntry.path("gamesPlayed").asInt(0));
                            player.setRegularSeasonPowerPlayGoals(seasonEntry.path("powerPlayGoals").asInt(0));
                            player.setRegularSeasonPowerPlayPoints(seasonEntry.path("powerPlayPoints").asInt(0));
                            player.setRegularSeasonAvgToi(seasonEntry.path("avgToi").asText(null));
                            foundRegular = true;
                        } else if (gameType == 3) {
                            player.setPlayoffGoals(seasonEntry.path("goals").asInt(0));
                            player.setPlayoffAssists(seasonEntry.path("assists").asInt(0));
                            player.setPlayoffPoints(seasonEntry.path("points").asInt(0));
                            player.setPlayoffGamesPlayed(seasonEntry.path("gamesPlayed").asInt(0));
                            player.setPlayoffPowerPlayGoals(seasonEntry.path("powerPlayGoals").asInt(0));
                            player.setPlayoffPowerPlayPoints(seasonEntry.path("powerPlayPoints").asInt(0));
                            player.setPlayoffAvgToi(seasonEntry.path("avgToi").asText(null));
                            foundPlayoff = true;
                        }
                    }
                }
            }
            log.info("  {} — RS:{} GP={} PTS={} | PO:{} GP={} PTS={}",
                    player.getFullName(),
                    foundRegular ? "found" : "missing",
                    player.getRegularSeasonGamesPlayed(),
                    player.getRegularSeasonPoints(),
                    foundPlayoff ? "found" : "none yet",
                    player.getPlayoffGamesPlayed(),
                    player.getPlayoffPoints());

            playerRepository.save(player);

            // Fetch game log for split stats (last 41 / last 20) — expensive
            syncGameLogSplits(player);
        } catch (Exception e) {
            log.error("Failed to sync stats for player {}", player.getFullName(), e);
        }
    }

    /**
     * Returns the game IDs of the team's last {@code count} COMPLETED regular-season games.
     * The schedule endpoint returns all games for the season; we sort by date and take the tail.
     */
    private Set<Long> getTeamLastNGameIds(String teamAbbrev, int count) {
        try {
            JsonNode schedule = nhlApiClient.get()
                    .uri("/club-schedule-season/{abbrev}/{season}", teamAbbrev, season)
                    .retrieve()
                    .bodyToMono(JsonNode.class)
                    .block();

            if (schedule == null || !schedule.has("games")) {
                log.warn("  No schedule data for {}", teamAbbrev);
                return Set.of();
            }

            List<JsonNode> completed = new ArrayList<>();
            schedule.get("games").forEach(game -> {
                // gameState "OFF" = completed; also accept "FINAL"
                String state = game.path("gameState").asText("");
                int type = game.path("gameType").asInt(0);
                // gameType 2 = regular season
                if (type == 2 && ("OFF".equals(state) || "FINAL".equals(state) || "7".equals(state))) {
                    completed.add(game);
                }
            });

            // Sort ascending by gameDate, then take the last `count`
            completed.sort(Comparator.comparing(g -> g.path("gameDate").asText("")));

            int from = Math.max(0, completed.size() - count);
            Set<Long> ids = new HashSet<>();
            for (int i = from; i < completed.size(); i++) {
                ids.add(completed.get(i).path("id").asLong());
            }
            log.info("  {} — {} completed RS games found, using last {} (ids: {} …)",
                    teamAbbrev, completed.size(), count, ids.stream().limit(3).toList());
            return ids;
        } catch (Exception e) {
            log.warn("  Failed to fetch schedule for {}: {}", teamAbbrev, e.getMessage());
            return Set.of();
        }
    }

    /**
     * Fetch game-by-game log and compute last-41 / last-20 stat splits
     * based on the TEAM's last N games of the regular season.
     */
    private void syncGameLogSplits(Player player) {
        log.info("  Fetching game log + team schedule for {} ({}) ...", player.getFullName(), player.getTeamAbbrev());
        try {
            // 1. Player's own game log
            JsonNode gameLog = nhlApiClient.get()
                    .uri("/player/{playerId}/game-log/{season}/2", player.getNhlPlayerId(), season)
                    .retrieve()
                    .bodyToMono(JsonNode.class)
                    .block();

            if (gameLog == null || !gameLog.has("gameLog")) {
                log.warn("  No game log data for {}", player.getFullName());
                return;
            }

            JsonNode games = gameLog.get("gameLog");
            if (!games.isArray() || games.isEmpty()) {
                log.warn("  Empty game log for {}", player.getFullName());
                return;
            }

            List<JsonNode> allPlayerGames = new ArrayList<>();
            games.forEach(allPlayerGames::add);
            log.info("  {} — {} games in player log", player.getFullName(), allPlayerGames.size());

            // 2. Team window game IDs (one schedule call covers both splits)
            Set<Long> last41Ids = getTeamLastNGameIds(player.getTeamAbbrev(), 41);
            Set<Long> last20Ids = getTeamLastNGameIds(player.getTeamAbbrev(), 20);

            // 3. Filter player log to only games within each team window
            List<JsonNode> games41 = allPlayerGames.stream()
                    .filter(g -> last41Ids.contains(g.path("gameId").asLong()))
                    .collect(Collectors.toList());
            List<JsonNode> games20 = allPlayerGames.stream()
                    .filter(g -> last20Ids.contains(g.path("gameId").asLong()))
                    .collect(Collectors.toList());

            computeSplit41(player, games41);
            computeSplit20(player, games20);

            log.info("  {} splits done — L41: {}pts in {}/{} gp | L20: {}pts in {}/{} gp",
                    player.getFullName(),
                    player.getLast41Points(), player.getLast41GamesPlayed(), last41Ids.size(),
                    player.getLast20Points(), player.getLast20GamesPlayed(), last20Ids.size());

            playerRepository.save(player);
        } catch (Exception e) {
            log.warn("Failed to sync game log splits for {}: {}", player.getFullName(), e.getMessage());
        }
    }

    private void computeSplit41(Player player, List<JsonNode> playerGamesInWindow) {
        int gp = 0, goals = 0, assists = 0, points = 0, ppg = 0, ppp = 0;
        long totalToiSeconds = 0;
        int toiCount = 0;

        for (JsonNode g : playerGamesInWindow) {
            gp++;
            goals   += g.path("goals").asInt(0);
            assists += g.path("assists").asInt(0);
            points  += g.path("points").asInt(0);
            ppg     += g.path("powerPlayGoals").asInt(0);
            ppp     += g.path("powerPlayPoints").asInt(0);
            String toi = g.path("toi").asText("");
            if (!toi.isEmpty()) {
                totalToiSeconds += toiToSeconds(toi);
                toiCount++;
            }
        }

        player.setLast41GamesPlayed(gp);
        player.setLast41Goals(goals);
        player.setLast41Assists(assists);
        player.setLast41Points(points);
        player.setLast41PowerPlayGoals(ppg);
        player.setLast41PowerPlayPoints(ppp);
        player.setLast41AvgToi(toiCount > 0 ? secondsToToi(totalToiSeconds / toiCount) : null);
    }

    private void computeSplit20(Player player, List<JsonNode> playerGamesInWindow) {
        int gp = 0, goals = 0, assists = 0, points = 0, ppg = 0, ppp = 0;
        long totalToiSeconds = 0;
        int toiCount = 0;

        for (JsonNode g : playerGamesInWindow) {
            gp++;
            goals   += g.path("goals").asInt(0);
            assists += g.path("assists").asInt(0);
            points  += g.path("points").asInt(0);
            ppg     += g.path("powerPlayGoals").asInt(0);
            ppp     += g.path("powerPlayPoints").asInt(0);
            String toi = g.path("toi").asText("");
            if (!toi.isEmpty()) {
                totalToiSeconds += toiToSeconds(toi);
                toiCount++;
            }
        }

        player.setLast20GamesPlayed(gp);
        player.setLast20Goals(goals);
        player.setLast20Assists(assists);
        player.setLast20Points(points);
        player.setLast20PowerPlayGoals(ppg);
        player.setLast20PowerPlayPoints(ppp);
        player.setLast20AvgToi(toiCount > 0 ? secondsToToi(totalToiSeconds / toiCount) : null);
    }

    /** Parses "MM:SS" or "H:MM:SS" into total seconds. */
    private long toiToSeconds(String toi) {
        if (toi == null || toi.isEmpty()) return 0;
        String[] parts = toi.split(":");
        try {
            if (parts.length == 2) {
                return Long.parseLong(parts[0]) * 60 + Long.parseLong(parts[1]);
            } else if (parts.length == 3) {
                return Long.parseLong(parts[0]) * 3600 + Long.parseLong(parts[1]) * 60 + Long.parseLong(parts[2]);
            }
        } catch (NumberFormatException ignored) {}
        return 0;
    }

    /** Formats total seconds back to "MM:SS". */
    private String secondsToToi(long totalSeconds) {
        return String.format("%d:%02d", totalSeconds / 60, totalSeconds % 60);
    }
}
