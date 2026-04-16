package com.nhlpool.config;

import com.nhlpool.domain.*;
import com.nhlpool.repository.*;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.CommandLineRunner;
import org.springframework.stereotype.Component;

import java.util.List;

@Component
@RequiredArgsConstructor
@Slf4j
public class DataInitializer implements CommandLineRunner {

    private final ScoringRuleRepository scoringRuleRepository;
    private final PredictionScoringRuleRepository predictionScoringRuleRepository;
    private final PoolRoundRepository poolRoundRepository;
    private final DraftConfigRepository draftConfigRepository;

    @Override
    public void run(String... args) {
        initScoringRules();
        initPredictionScoringRules();
        initPoolRounds();
        initDraftConfig();
    }

    private void initScoringRules() {
        if (scoringRuleRepository.count() > 0) return;
        log.info("Initializing default scoring rules");
        scoringRuleRepository.saveAll(List.of(
                ScoringRule.builder().statName("GOAL").pointValue(1).enabled(true).build(),
                ScoringRule.builder().statName("ASSIST").pointValue(1).enabled(true).build()
        ));
    }

    private void initPredictionScoringRules() {
        if (predictionScoringRuleRepository.count() > 0) return;
        log.info("Initializing default prediction scoring rules");
        predictionScoringRuleRepository.saveAll(List.of(
                PredictionScoringRule.builder().roundNumber(0).correctWinnerPoints(0).correctGamesBonus(0).connSmytheBonus(15).build(),
                PredictionScoringRule.builder().roundNumber(1).correctWinnerPoints(7).correctGamesBonus(3).build(),
                PredictionScoringRule.builder().roundNumber(2).correctWinnerPoints(10).correctGamesBonus(3).build(),
                PredictionScoringRule.builder().roundNumber(3).correctWinnerPoints(15).correctGamesBonus(3).build(),
                PredictionScoringRule.builder().roundNumber(4).correctWinnerPoints(20).correctGamesBonus(3).build()
        ));
    }

    private void initPoolRounds() {
        if (poolRoundRepository.count() > 0) return;
        log.info("Initializing playoff rounds");
        poolRoundRepository.saveAll(List.of(
                PoolRound.builder().roundNumber(1).label("1st Round").status(RoundStatus.UPCOMING).build(),
                PoolRound.builder().roundNumber(2).label("2nd Round").status(RoundStatus.UPCOMING).build(),
                PoolRound.builder().roundNumber(3).label("Conference Finals").status(RoundStatus.UPCOMING).build(),
                PoolRound.builder().roundNumber(4).label("Stanley Cup Final").status(RoundStatus.UPCOMING).build()
        ));
    }

    private void initDraftConfig() {
        if (draftConfigRepository.count() > 0) return;
        log.info("Initializing draft config");
        draftConfigRepository.save(
                DraftConfig.builder()
                        .playersPerTeam(10)
                        .status(DraftStatus.NOT_STARTED)
                        .currentPickNumber(0)
                        .totalTeams(5)
                        .build()
        );
    }
}
