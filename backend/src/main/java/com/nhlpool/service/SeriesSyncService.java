package com.nhlpool.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.nhlpool.domain.PoolRound;
import com.nhlpool.domain.Series;
import com.nhlpool.repository.PoolRoundRepository;
import com.nhlpool.repository.SeriesRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
@RequiredArgsConstructor
@Slf4j
public class SeriesSyncService {

    private final NhlApiService nhlApiService;
    private final PoolRoundRepository poolRoundRepository;
    private final SeriesRepository seriesRepository;

    /**
     * Fetches the current playoff bracket from the NHL API and upserts all series
     * records (wins, logos, winner) into the database.
     */
    public void syncSeriesFromApi() {
        JsonNode bracket = nhlApiService.getPlayoffBracket();
        if (bracket == null || !bracket.has("rounds")) {
            log.warn("No bracket data returned from NHL API — series sync skipped");
            return;
        }

        int synced = 0;
        bracket.get("rounds").forEach(roundNode -> {
            int roundNumber = roundNode.path("roundNumber").asInt();
            PoolRound poolRound = poolRoundRepository.findByRoundNumber(roundNumber).orElse(null);
            if (poolRound == null) {
                log.warn("No PoolRound found for roundNumber={} — skipping", roundNumber);
                return;
            }

            JsonNode seriesArray = roundNode.path("series");
            if (!seriesArray.isArray()) return;

            seriesArray.forEach(seriesNode -> {
                String seriesCode = seriesNode.path("seriesLetter").asText();
                String topAbbrev   = seriesNode.path("topSeed").path("abbrev").asText();
                String bottomAbbrev = seriesNode.path("bottomSeed").path("abbrev").asText();
                int topWins    = seriesNode.path("topSeed").path("wins").asInt(0);
                int bottomWins = seriesNode.path("bottomSeed").path("wins").asInt(0);
                String topLogo    = seriesNode.path("topSeed").path("darkLogo").asText("");
                String bottomLogo = seriesNode.path("bottomSeed").path("darkLogo").asText("");

                // Find or create this series
                List<Series> existing = seriesRepository.findByRoundNumber(roundNumber);
                Series series = existing.stream()
                        .filter(s -> s.getSeriesCode().equals(seriesCode))
                        .findFirst()
                        .orElse(Series.builder()
                                .round(poolRound)
                                .seriesCode(seriesCode)
                                .topSeedAbbrev(topAbbrev)
                                .bottomSeedAbbrev(bottomAbbrev)
                                .build());

                series.setTopSeedWins(topWins);
                series.setBottomSeedWins(bottomWins);
                series.setTopSeedLogoUrl(topLogo);
                series.setBottomSeedLogoUrl(bottomLogo);

                // Mark winner if series is over
                int winningTeamId = seriesNode.path("winningTeamId").asInt(0);
                if (winningTeamId > 0) {
                    int topId = seriesNode.path("topSeed").path("id").asInt();
                    series.setWinnerAbbrev(winningTeamId == topId ? topAbbrev : bottomAbbrev);
                }

                seriesRepository.save(series);
            });
        });

        log.info("Series sync complete");
    }
}
