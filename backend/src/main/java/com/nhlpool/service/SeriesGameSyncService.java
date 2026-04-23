package com.nhlpool.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.nhlpool.domain.Series;
import com.nhlpool.domain.SeriesGame;
import com.nhlpool.repository.SeriesGameRepository;
import com.nhlpool.repository.SeriesRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;

/**
 * Maintains a local DB cache of ALL playoff game records per series:
 *  - PRE  : scheduled but not yet played (score 0-0)
 *  - LIVE : in progress — updated every ~30s by the scheduler
 *  - FINAL/OFF : completed — written once, never changed again
 *
 * Design goals:
 *  - The /api/predictions/series/{id}/games endpoint becomes a pure DB read.
 *  - The live watcher calls updateLiveGame() to keep the LIVE row current.
 *  - Post-game, syncAllSeriesGames() finalises the row and scans for the next PRE game.
 *  - Scan window extends 14 days ahead so future scheduled games are pre-populated.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class SeriesGameSyncService {

    private static final ZoneId EASTERN = ZoneId.of("America/New_York");
    /** How many days ahead to look for the next scheduled game. */
    private static final int LOOK_AHEAD_DAYS = 14;

    private final NhlApiService        nhlApiService;
    private final SeriesRepository     seriesRepository;
    private final SeriesGameRepository seriesGameRepository;

    // -------------------------------------------------------------------------
    // Full sync (called post-game and on demand)
    // -------------------------------------------------------------------------

    @Transactional
    public void syncAllSeriesGames() {
        LocalDate playoffStart = nhlApiService.getPlayoffStartDate();
        for (Series series : seriesRepository.findAllWithRound()) {
            try {
                syncSeriesGames(series, playoffStart);
            } catch (Exception e) {
                log.warn("[GameSync] Failed for series {}: {}", series.getSeriesCode(), e.getMessage());
            }
        }
    }

    @Transactional
    public void syncSeriesGames(Series series, LocalDate playoffStart) {
        LocalDate today  = LocalDate.now(EASTERN);
        LocalDate cutoff = today.plusDays(LOOK_AHEAD_DAYS);

        // Start scanning from the day of the last known game (re-check that day in case
        // it was mid-game last time we ran), or from playoff start if nothing cached yet.
        List<SeriesGame> cached = seriesGameRepository.findBySeriesIdOrderByGameNumberAsc(series.getId());
        LocalDate scanFrom = cached.isEmpty()
                ? playoffStart
                : cached.get(cached.size() - 1).getGameDate();

        for (LocalDate date = scanFrom; !date.isAfter(cutoff); date = date.plusDays(1)) {
            try {
                JsonNode schedule = nhlApiService.getNhlApiClient().get()
                        .uri("/schedule/{date}", date.toString())
                        .retrieve()
                        .bodyToMono(JsonNode.class)
                        .block();

                if (schedule == null || !schedule.has("gameWeek")) continue;

                for (JsonNode dayNode : schedule.get("gameWeek")) {
                    if (!date.toString().equals(dayNode.path("date").asText(""))) continue;

                    for (JsonNode game : dayNode.path("games")) {
                        if (game.path("gameType").asInt(0) != 3) continue;

                        JsonNode ss = game.path("seriesStatus");
                        if (ss.isMissingNode()) continue;
                        if (!series.getSeriesCode().equalsIgnoreCase(ss.path("seriesLetter").asText(""))) continue;

                        String state      = game.path("gameState").asText("PRE");
                        int    gameNumber = ss.path("gameNumberOfSeries").asInt(0);
                        if (gameNumber <= 0) continue;

                        // Skip in-progress games — handled by updateLiveGame() from the scheduler
                        if ("LIVE".equals(state) || "CRIT".equals(state)) continue;

                        String awayAbbrev = game.path("awayTeam").path("abbrev").asText("");
                        String homeAbbrev = game.path("homeTeam").path("abbrev").asText("");

                        boolean isFinal = "OFF".equals(state) || "FINAL".equals(state);
                        int awayScore = isFinal ? game.path("awayTeam").path("score").asInt(0) : 0;
                        int homeScore = isFinal ? game.path("homeTeam").path("score").asInt(0) : 0;

                        JsonNode pd      = game.path("periodDescriptor");
                        String periodType = isFinal ? pd.path("periodType").asText("REG") : "REG";

                        // Upsert: don't overwrite a FINAL/OFF row with a stale PRE state
                        SeriesGame sg = seriesGameRepository
                                .findBySeriesIdAndGameNumber(series.getId(), gameNumber)
                                .orElseGet(() -> SeriesGame.builder()
                                        .series(series)
                                        .gameNumber(gameNumber)
                                        .build());

                        // Never downgrade a finished row back to PRE
                        boolean alreadyFinal = "OFF".equals(sg.getGameState()) || "FINAL".equals(sg.getGameState());
                        if (alreadyFinal && !isFinal) continue;

                        sg.setAwayAbbrev(awayAbbrev);
                        sg.setHomeAbbrev(homeAbbrev);
                        sg.setAwayScore(awayScore);
                        sg.setHomeScore(homeScore);
                        sg.setGameState(isFinal ? state : "PRE");
                        sg.setPeriodType(periodType);
                        sg.setGameDate(date);

                        seriesGameRepository.save(sg);
                        log.debug("[GameSync] Upserted G{} ({} @ {}) state={} for series {}",
                                gameNumber, awayAbbrev, homeAbbrev, sg.getGameState(), series.getSeriesCode());
                    }
                }
            } catch (Exception e) {
                log.warn("[GameSync] Schedule fetch failed for {}: {}", date, e.getMessage());
            }
        }

        log.debug("[GameSync] Series {} done — {} rows in series_game",
                series.getSeriesCode(),
                seriesGameRepository.findBySeriesIdOrderByGameNumberAsc(series.getId()).size());
    }

    // -------------------------------------------------------------------------
    // Live update (called every 30s by the scheduler when a game is LIVE/CRIT)
    // -------------------------------------------------------------------------

    /**
     * Scans today's NHL schedule for ALL currently LIVE/CRIT playoff games
     * and writes their current score + period/clock to series_game.
     * Called every 30s from the scheduler — one schedule API call covers all live series.
     */
    @Transactional
    public void updateAllLiveGames() {
        LocalDate today = LocalDate.now(EASTERN);
        try {
            JsonNode schedule = nhlApiService.getNhlApiClient().get()
                    .uri("/schedule/{date}", today.toString())
                    .retrieve()
                    .bodyToMono(JsonNode.class)
                    .block();

            if (schedule == null || !schedule.has("gameWeek")) return;

            // Build a lookup of seriesCode -> Series for quick access
            java.util.Map<String, Series> seriesByCode = seriesRepository.findAllWithRound().stream()
                    .collect(java.util.stream.Collectors.toMap(
                            s -> s.getSeriesCode().toUpperCase(),
                            s -> s,
                            (a, b) -> a
                    ));

            for (JsonNode dayNode : schedule.get("gameWeek")) {
                for (JsonNode game : dayNode.path("games")) {
                    if (game.path("gameType").asInt(0) != 3) continue;

                    String state = game.path("gameState").asText("");
                    if (!"LIVE".equals(state) && !"CRIT".equals(state)) continue;

                    JsonNode ss = game.path("seriesStatus");
                    if (ss.isMissingNode()) continue;

                    String seriesLetter = ss.path("seriesLetter").asText("").toUpperCase();
                    int    gameNumber   = ss.path("gameNumberOfSeries").asInt(0);
                    Series series       = seriesByCode.get(seriesLetter);

                    if (series == null || gameNumber <= 0) continue;

                    String awayAbbrev = game.path("awayTeam").path("abbrev").asText("");
                    String homeAbbrev = game.path("homeTeam").path("abbrev").asText("");
                    int    awayScore  = game.path("awayTeam").path("score").asInt(0);
                    int    homeScore  = game.path("homeTeam").path("score").asInt(0);

                    JsonNode pd       = game.path("periodDescriptor");
                    int    periodNum  = pd.path("number").asInt(0);
                    String periodType = pd.path("periodType").asText("REG");
                    String timeLeft   = game.path("clock").path("timeRemaining").asText("");

                    SeriesGame sg = seriesGameRepository
                            .findBySeriesIdAndGameNumber(series.getId(), gameNumber)
                            .orElseGet(() -> SeriesGame.builder()
                                    .series(series)
                                    .gameNumber(gameNumber)
                                    .gameDate(today)
                                    .periodNumber(0)
                                    .timeRemaining("")
                                    .build());

                    // Race-condition guard: never downgrade a finished row
                    if ("OFF".equals(sg.getGameState()) || "FINAL".equals(sg.getGameState())) continue;

                    sg.setAwayAbbrev(awayAbbrev);
                    sg.setHomeAbbrev(homeAbbrev);
                    sg.setAwayScore(awayScore);
                    sg.setHomeScore(homeScore);
                    sg.setGameState(state);
                    sg.setPeriodType(periodType);
                    sg.setPeriodNumber(periodNum);
                    sg.setTimeRemaining(timeLeft);
                    sg.setGameDate(today);

                    seriesGameRepository.save(sg);
                    log.debug("[GameSync] Live update: G{} {} {} @ {} {} ({} {} {})",
                            gameNumber, awayAbbrev, awayScore, homeAbbrev, homeScore,
                            state, periodType, timeLeft);
                }
            }
        } catch (Exception e) {
            log.warn("[GameSync] updateAllLiveGames failed: {}", e.getMessage());
        }
    }

    /**
     * Updates the series_game row for a single live game by gameId.
     * Delegates to the schedule-based approach for reliability.
     * Kept for backward-compat with per-game scheduler calls.
     */
    @Transactional
    public void updateLiveGame(long gameId) {
        // The schedule-based scan is more reliable; just run the full live scan.
        // It's cheap (one API call) and covers all live series at once.
        updateAllLiveGames();
    }
}
