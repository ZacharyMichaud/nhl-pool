package com.nhlpool.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.nhlpool.domain.Player;
import com.nhlpool.repository.PlayerRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;

import java.util.*;

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
        teamAbbrevs.forEach(abbrev -> {
            try {
                JsonNode roster = nhlApiClient.get()
                        .uri("/roster/{abbrev}/{season}", abbrev, season)
                        .retrieve()
                        .bodyToMono(JsonNode.class)
                        .block();

                if (roster != null) {
                    processRosterGroup(roster, "forwards", abbrev);
                    processRosterGroup(roster, "defensemen", abbrev);
                    processRosterGroup(roster, "goalies", abbrev);
                }
                log.info("Synced roster for {}", abbrev);
            } catch (Exception e) {
                log.error("Failed to sync roster for {}", abbrev, e);
            }
        });
    }

    private void processRosterGroup(JsonNode roster, String group, String teamAbbrev) {
        if (!roster.has(group)) return;
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
            }
        });
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

                    if ("NHL".equals(league) && seasonStr.equals(season)) {
                        if (gameType == 2) {
                            player.setRegularSeasonGoals(seasonEntry.path("goals").asInt(0));
                            player.setRegularSeasonAssists(seasonEntry.path("assists").asInt(0));
                            player.setRegularSeasonPoints(seasonEntry.path("points").asInt(0));
                            player.setRegularSeasonGamesPlayed(seasonEntry.path("gamesPlayed").asInt(0));
                            player.setRegularSeasonPowerPlayGoals(seasonEntry.path("powerPlayGoals").asInt(0));
                            player.setRegularSeasonPowerPlayPoints(seasonEntry.path("powerPlayPoints").asInt(0));
                            player.setRegularSeasonAvgToi(seasonEntry.path("avgToi").asText(null));
                        } else if (gameType == 3) {
                            player.setPlayoffGoals(seasonEntry.path("goals").asInt(0));
                            player.setPlayoffAssists(seasonEntry.path("assists").asInt(0));
                            player.setPlayoffPoints(seasonEntry.path("points").asInt(0));
                            player.setPlayoffGamesPlayed(seasonEntry.path("gamesPlayed").asInt(0));
                            player.setPlayoffPowerPlayGoals(seasonEntry.path("powerPlayGoals").asInt(0));
                            player.setPlayoffPowerPlayPoints(seasonEntry.path("powerPlayPoints").asInt(0));
                            player.setPlayoffAvgToi(seasonEntry.path("avgToi").asText(null));
                        }
                    }
                });
            }

            playerRepository.save(player);

            // Fetch game log for split stats (last 41 / last 20) — expensive
            syncGameLogSplits(player);
        } catch (Exception e) {
            log.error("Failed to sync stats for player {}", player.getFullName(), e);
        }
    }

    /**
     * Fetch game-by-game log and compute last-41 / last-20 stat splits
     */
    private void syncGameLogSplits(Player player) {
        try {
            JsonNode gameLog = nhlApiClient.get()
                    .uri("/player/{playerId}/game-log/{season}/2", player.getNhlPlayerId(), season)
                    .retrieve()
                    .bodyToMono(JsonNode.class)
                    .block();

            if (gameLog == null || !gameLog.has("gameLog")) return;

            JsonNode games = gameLog.get("gameLog");
            if (!games.isArray() || games.isEmpty()) return;

            // Games come most-recent first from the API
            List<JsonNode> gameList = new ArrayList<>();
            games.forEach(gameList::add);

            computeSplit(player, gameList, 41);
            computeSplit(player, gameList, 20);

            playerRepository.save(player);
        } catch (Exception e) {
            log.warn("Failed to sync game log splits for {}: {}", player.getFullName(), e.getMessage());
        }
    }

    private void computeSplit(Player player, List<JsonNode> games, int count) {
        int limit = Math.min(count, games.size());
        int gp = limit;
        int goals = 0, assists = 0, points = 0, ppg = 0, ppp = 0;

        for (int i = 0; i < limit; i++) {
            JsonNode g = games.get(i);
            goals += g.path("goals").asInt(0);
            assists += g.path("assists").asInt(0);
            points += g.path("points").asInt(0);
            ppg += g.path("powerPlayGoals").asInt(0);
            ppp += g.path("powerPlayPoints").asInt(0);
        }

        if (count == 41) {
            player.setLast41GamesPlayed(gp);
            player.setLast41Goals(goals);
            player.setLast41Assists(assists);
            player.setLast41Points(points);
            player.setLast41PowerPlayGoals(ppg);
            player.setLast41PowerPlayPoints(ppp);
        } else {
            player.setLast20GamesPlayed(gp);
            player.setLast20Goals(goals);
            player.setLast20Assists(assists);
            player.setLast20Points(points);
            player.setLast20PowerPlayGoals(ppg);
            player.setLast20PowerPlayPoints(ppp);
        }
    }
}
