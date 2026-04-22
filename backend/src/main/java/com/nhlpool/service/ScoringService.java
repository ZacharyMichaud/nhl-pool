package com.nhlpool.service;

import com.nhlpool.domain.*;
import com.nhlpool.repository.*;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.*;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class ScoringService {

    private final ScoringRuleRepository scoringRuleRepository;
    private final PredictionScoringRuleRepository predictionScoringRuleRepository;
    private final DraftPickRepository draftPickRepository;
    private final PredictionRepository predictionRepository;
    private final PoolTeamRepository poolTeamRepository;
    private final PlayerRepository playerRepository;

    public record TeamStanding(
            Long teamId, String teamName, List<String> memberNames,
            int playerPoints, int predictionPoints, int totalPoints,
            int activePlayers, int eliminatedPlayers,
            List<PlayerScore> playerScores,
            Long connSmythePredictionPlayerId, String connSmythePlayerName,
            String connSmytheHeadshotUrl, String connSmythePosition, String connSmytheTeamAbbrev,
            int connSmytheGoals, int connSmytheAssists, int connSmytheGamesPlayed, int connSmythePoints,
            int connSmythePowerPlayGoals, int connSmythePowerPlayPoints, String connSmytheAvgToi) {}

    public record PlayerScore(
            Long playerId, String playerName, String position, String teamAbbrev,
            String headshotUrl, int points, int goals, int assists,
            int gamesPlayed, boolean eliminated) {}

    @Transactional(readOnly = true)
    public List<TeamStanding> getStandings() {
        List<PoolTeam> teams = poolTeamRepository.findAllWithMembers();
        Map<String, ScoringRule> rules = scoringRuleRepository.findAll().stream()
                .collect(Collectors.toMap(ScoringRule::getStatName, r -> r));

        return teams.stream().map(team -> {
            // Use fetch-join query to eagerly load player + team, avoiding LazyInitializationException
            List<DraftPick> picks = draftPickRepository.findByTeamIdWithPlayerOrderByPickNumberAsc(team.getId());

            List<PlayerScore> playerScores = picks.stream().map(pick -> {
                Player p = pick.getPlayer();
                int pts = calculatePlayerPoints(p, rules);
                return new PlayerScore(p.getId(), p.getFullName(), p.getPosition(),
                        p.getTeamAbbrev(), p.getHeadshotUrl(), pts,
                        p.getPlayoffGoals(), p.getPlayoffAssists(),
                        p.getPlayoffGamesPlayed(), p.getEliminated());
            }).sorted(Comparator.comparingInt(PlayerScore::points).reversed())
              .collect(Collectors.toList());

            int playerPoints = playerScores.stream().mapToInt(PlayerScore::points).sum();
            int predictionPoints = calculatePredictionPoints(team);
            int activePlayers = (int) playerScores.stream().filter(ps -> !ps.eliminated()).count();
            int eliminatedPlayers = playerScores.size() - activePlayers;

            List<String> memberNames = team.getMembers().stream()
                    .map(User::getDisplayName).collect(Collectors.toList());

            Long csPlayerId = team.getConnSmythePredictionPlayerId();
            String csPlayerName = null;
            String csHeadshotUrl = null;
            String csPosition = null;
            String csTeamAbbrev = null;
            int csGoals = 0, csAssists = 0, csGp = 0, csPts = 0, csPpg = 0, csPpp = 0;
            String csToi = null;
            if (csPlayerId != null) {
                var csPlayer = playerRepository.findById(csPlayerId).orElse(null);
                if (csPlayer != null) {
                    csPlayerName = csPlayer.getFullName();
                    csHeadshotUrl = csPlayer.getHeadshotUrl();
                    csPosition    = csPlayer.getPosition();
                    csTeamAbbrev  = csPlayer.getTeamAbbrev();
                    csGoals       = csPlayer.getPlayoffGoals() != null ? csPlayer.getPlayoffGoals() : 0;
                    csAssists     = csPlayer.getPlayoffAssists() != null ? csPlayer.getPlayoffAssists() : 0;
                    csGp          = csPlayer.getPlayoffGamesPlayed() != null ? csPlayer.getPlayoffGamesPlayed() : 0;
                    csPts         = csGoals + csAssists;
                    csPpg         = csPlayer.getPlayoffPowerPlayGoals() != null ? csPlayer.getPlayoffPowerPlayGoals() : 0;
                    csPpp         = csPlayer.getPlayoffPowerPlayPoints() != null ? csPlayer.getPlayoffPowerPlayPoints() : 0;
                    csToi         = csPlayer.getPlayoffAvgToi();
                }
            }

            return new TeamStanding(team.getId(), team.getName(), memberNames,
                    playerPoints, predictionPoints, playerPoints + predictionPoints,
                    activePlayers, eliminatedPlayers, playerScores,
                    csPlayerId, csPlayerName, csHeadshotUrl, csPosition, csTeamAbbrev,
                    csGoals, csAssists, csGp, csPts, csPpg, csPpp, csToi);
        }).sorted(Comparator.comparingInt(TeamStanding::totalPoints).reversed())
          .collect(Collectors.toList());
    }

    private int calculatePlayerPoints(Player player, Map<String, ScoringRule> rules) {
        int points = 0;
        points += getStatPoints(rules, "GOAL", player.getPlayoffGoals());
        points += getStatPoints(rules, "ASSIST", player.getPlayoffAssists());
        return points;
    }

    private int getStatPoints(Map<String, ScoringRule> rules, String stat, int value) {
        ScoringRule rule = rules.get(stat);
        return (rule != null && rule.getEnabled()) ? value * rule.getPointValue() : 0;
    }

    private int calculatePredictionPoints(PoolTeam team) {
        List<Prediction> predictions = predictionRepository.findByTeamId(team.getId());
        List<PredictionScoringRule> predRules = predictionScoringRuleRepository.findAll();
        Map<Integer, PredictionScoringRule> ruleMap = predRules.stream()
                .collect(Collectors.toMap(PredictionScoringRule::getRoundNumber, r -> r));

        int points = 0;
        for (Prediction pred : predictions) {
            Series series = pred.getSeries();
            if (!series.isCompleted()) continue;

            int roundNum = series.getRound().getRoundNumber();
            PredictionScoringRule rule = ruleMap.get(roundNum);
            if (rule == null) continue;

            // Correct winner?
            if (pred.getPredictedWinnerAbbrev().equals(series.getWinnerAbbrev())) {
                points += rule.getCorrectWinnerPoints();
                // Correct number of games?
                if (pred.getPredictedGames().equals(series.getTotalGames())) {
                    points += rule.getCorrectGamesBonus();
                }
            }
        }

        // Conn Smythe bonus — handled separately (checked at end of playoffs)
        // This will be calculated when admin marks the Conn Smythe winner
        return points;
    }

    public List<ScoringRule> getAllScoringRules() {
        return scoringRuleRepository.findAll();
    }

    public List<PredictionScoringRule> getAllPredictionScoringRules() {
        return predictionScoringRuleRepository.findAll();
    }

    public ScoringRule updateScoringRule(Long id, Integer pointValue, Boolean enabled) {
        ScoringRule rule = scoringRuleRepository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("Scoring rule not found"));
        if (pointValue != null) rule.setPointValue(pointValue);
        if (enabled != null) rule.setEnabled(enabled);
        return scoringRuleRepository.save(rule);
    }

    public PredictionScoringRule updatePredictionScoringRule(Long id, Integer correctWinnerPoints,
                                                              Integer correctGamesBonus, Integer connSmytheBonus) {
        PredictionScoringRule rule = predictionScoringRuleRepository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("Prediction scoring rule not found"));
        if (correctWinnerPoints != null) rule.setCorrectWinnerPoints(correctWinnerPoints);
        if (correctGamesBonus != null) rule.setCorrectGamesBonus(correctGamesBonus);
        if (connSmytheBonus != null) rule.setConnSmytheBonus(connSmytheBonus);
        return predictionScoringRuleRepository.save(rule);
    }
}
