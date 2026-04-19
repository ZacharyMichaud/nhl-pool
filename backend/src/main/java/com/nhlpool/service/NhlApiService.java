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
     * Returns today's playoff games mapped as gameId → startTimeUTC.
     * Uses /schedule/now which returns the current week of games.
     */
    public Map<Long, Instant> getTodaysPlayoffGames() {
        try {
            JsonNode schedule = nhlApiClient.get()
                    .uri("/schedule/now")
                    .retrieve()
                    .bodyToMono(JsonNode.class)
                    .block();

            if (schedule == null || !schedule.has("gameWeek")) return Map.of();

            String today = LocalDate.now(ZoneOffset.UTC).toString(); // e.g. "2026-04-19"
            Map<Long, Instant> result = new LinkedHashMap<>();

            schedule.get("gameWeek").forEach(dayNode -> {
                String date = dayNode.path("date").asText("");
                if (!date.equals(today)) return;

                dayNode.path("games").forEach(game -> {
                    int gameType = game.path("gameType").asInt(0);
                    if (gameType != 3) return; // playoffs only

                    long gameId = game.path("id").asLong();
                    String startUtc = game.path("startTimeUTC").asText("");
                    if (gameId > 0 && !startUtc.isEmpty()) {
                        result.put(gameId, Instant.parse(startUtc));
                    }
                });
            });

            log.info("Found {} playoff game(s) today ({})", result.size(), today);
            return result;
        } catch (Exception e) {
            log.error("Failed to fetch today's playoff schedule", e);
            return Map.of();
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
     * Returns the current gameState for a specific game ID from /schedule/now.
     * States: "FUT" (upcoming), "LIVE"/"CRIT" (in progress), "OFF" (finished).
     * Returns "" if the game is not found or the call fails.
     */
    public String getGameState(long gameId) {
        try {
            JsonNode schedule = nhlApiClient.get()
                    .uri("/schedule/now")
                    .retrieve()
                    .bodyToMono(JsonNode.class)
                    .block();

            if (schedule == null) return "";

            for (JsonNode dayNode : schedule.path("gameWeek")) {
                for (JsonNode game : dayNode.path("games")) {
                    if (game.path("id").asLong() == gameId) {
                        return game.path("gameState").asText("");
                    }
                }
            }
        } catch (Exception e) {
            log.warn("Failed to fetch gameState for game {}: {}", gameId, e.getMessage());
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
     * Sync ONLY playoff stats — fast, used by the scheduled job
     */
    public void syncPlayoffStats(Player player) {
        try {
            JsonNode landing = nhlApiClient.get()
                    .uri("/player/{playerId}/landing", player.getNhlPlayerId())
                    .retrieve()
                    .bodyToMono(JsonNode.class)
                    .block();

            if (landing == null) return;

            JsonNode seasonTotals = landing.path("seasonTotals");
            if (seasonTotals.isArray()) {
                seasonTotals.forEach(seasonEntry -> {
                    String league = seasonEntry.path("leagueAbbrev").asText("");
                    int gameType = seasonEntry.path("gameTypeId").asInt(0);
                    String seasonStr = String.valueOf(seasonEntry.path("season").asInt(0));

                    if ("NHL".equals(league) && seasonStr.equals(season) && gameType == 3) {
                        player.setPlayoffGoals(seasonEntry.path("goals").asInt(0));
                        player.setPlayoffAssists(seasonEntry.path("assists").asInt(0));
                        player.setPlayoffPoints(seasonEntry.path("points").asInt(0));
                        player.setPlayoffGamesPlayed(seasonEntry.path("gamesPlayed").asInt(0));
                        player.setPlayoffPowerPlayGoals(seasonEntry.path("powerPlayGoals").asInt(0));
                        player.setPlayoffPowerPlayPoints(seasonEntry.path("powerPlayPoints").asInt(0));
                        player.setPlayoffAvgToi(seasonEntry.path("avgToi").asText(null));
                    }
                });
            }
            playerRepository.save(player);
        } catch (Exception e) {
            log.error("Failed to sync playoff stats for player {}", player.getFullName(), e);
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
