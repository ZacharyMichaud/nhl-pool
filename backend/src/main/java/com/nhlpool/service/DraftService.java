package com.nhlpool.service;

import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;

import com.nhlpool.domain.DraftConfig;
import com.nhlpool.domain.DraftPick;
import com.nhlpool.domain.DraftStatus;
import com.nhlpool.domain.Player;
import com.nhlpool.domain.PoolTeam;
import com.nhlpool.repository.DraftConfigRepository;
import com.nhlpool.repository.DraftPickRepository;
import com.nhlpool.repository.PlayerRepository;
import com.nhlpool.repository.PoolTeamRepository;
import com.nhlpool.repository.WatchlistRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class DraftService {

    private final DraftConfigRepository draftConfigRepository;
    private final DraftPickRepository draftPickRepository;
    private final PlayerRepository playerRepository;
    private final PoolTeamRepository poolTeamRepository;
    private final WatchlistRepository watchlistRepository;

    public DraftConfig getConfig() {
        return draftConfigRepository.findAll().stream().findFirst()
                .orElseThrow(() -> new IllegalStateException("Draft config not initialized"));
    }

    @Transactional
    public DraftConfig updateConfig(Integer playersPerTeam, String draftOrder) {
        DraftConfig config = getConfig();
        if (config.getStatus() != DraftStatus.NOT_STARTED) {
            throw new IllegalStateException("Cannot modify draft config after draft has started");
        }
        if (playersPerTeam != null) {
            config.setPlayersPerTeam(playersPerTeam);
        }
        if (draftOrder != null) {
            config.setDraftOrder(draftOrder);
        }
        return draftConfigRepository.save(config);
    }

    @Transactional
    public DraftConfig startDraft() {
        DraftConfig config = getConfig();
        if (config.getStatus() != DraftStatus.NOT_STARTED) {
            throw new IllegalStateException("Draft has already been started");
        }
        if (config.getDraftOrder() == null || config.getDraftOrder().isBlank()) {
            // Default order: all teams by ID
            List<PoolTeam> teams = poolTeamRepository.findAll();
            config.setDraftOrder(teams.stream().map(t -> String.valueOf(t.getId())).collect(Collectors.joining(",")));
            config.setTotalTeams(teams.size());
        }
        config.setStatus(DraftStatus.IN_PROGRESS);
        config.setCurrentPickNumber(1);
        return draftConfigRepository.save(config);
    }

    @Transactional
    public DraftPick makePick(Long teamId, Long playerId) {
        DraftConfig config = getConfig();
        if (config.getStatus() != DraftStatus.IN_PROGRESS) {
            throw new IllegalStateException("Draft is not in progress");
        }

        // Validate it's this team's turn
        Long expectedTeamId = getTeamForPick(config, config.getCurrentPickNumber());
        if (!expectedTeamId.equals(teamId)) {
            throw new IllegalStateException("It's not your team's turn to pick");
        }

        // Validate player is available
        Player player = playerRepository.findById(playerId)
                .orElseThrow(() -> new IllegalArgumentException("Player not found"));
        if (player.getDrafted()) {
            throw new IllegalStateException("Player has already been drafted");
        }

        // Validate team hasn't exceeded their limit
        long teamPickCount = draftPickRepository.countByTeamId(teamId);
        if (teamPickCount >= config.getPlayersPerTeam()) {
            throw new IllegalStateException("Team has already drafted the maximum number of players");
        }

        // Make the pick
        PoolTeam team = poolTeamRepository.findById(teamId)
                .orElseThrow(() -> new IllegalArgumentException("Team not found"));

        DraftPick pick = DraftPick.builder()
                .team(team)
                .player(player)
                .pickNumber(config.getCurrentPickNumber())
                .build();
        pick = draftPickRepository.save(pick);

        player.setDrafted(true);
        playerRepository.save(player);

        // Remove the drafted player from every team's watchlist
        watchlistRepository.deleteByPlayerId(playerId);

        // Advance pick
        config.setCurrentPickNumber(config.getCurrentPickNumber() + 1);

        // Check if draft is complete
        int totalPicks = config.getTotalTeams() * config.getPlayersPerTeam();
        if (config.getCurrentPickNumber() > totalPicks) {
            config.setStatus(DraftStatus.COMPLETED);
        }
        draftConfigRepository.save(config);

        return pick;
    }

    /**
     * Snake draft: odd rounds go forward, even rounds go backward
     * Round 1: 1,2,3,4,5
     * Round 2: 5,4,3,2,1
     * Round 3: 1,2,3,4,5
     * ...
     */
    public Long getTeamForPick(DraftConfig config, int pickNumber) {
        List<Long> order = Arrays.stream(config.getDraftOrder().split(","))
                .map(String::trim)
                .map(Long::parseLong)
                .collect(Collectors.toList());

        int numTeams = order.size();
        int zeroBasedPick = pickNumber - 1;
        int round = zeroBasedPick / numTeams; // 0-based round
        int positionInRound = zeroBasedPick % numTeams;

        // Snake: even rounds (0, 2, 4...) go forward, odd rounds (1, 3, 5...) go backward
        if (round % 2 == 0) {
            return order.get(positionInRound);
        } else {
            return order.get(numTeams - 1 - positionInRound);
        }
    }

    public List<DraftPick> getDraftBoard() {
        return draftPickRepository.findAllWithPlayerAndTeamOrderByPickNumber();
    }

    public record DraftOrderEntry(int pickNumber, Long teamId, String teamName) { }

    public List<DraftOrderEntry> getFullDraftOrder() {
        DraftConfig config = getConfig();
        List<PoolTeam> teams = poolTeamRepository.findAll();
        var teamMap = teams.stream().collect(Collectors.toMap(PoolTeam::getId, PoolTeam::getName));

        int totalPicks = config.getTotalTeams() * config.getPlayersPerTeam();
        return java.util.stream.IntStream.rangeClosed(1, totalPicks)
                .mapToObj(pick -> {
                    Long teamId = getTeamForPick(config, pick);
                    return new DraftOrderEntry(pick, teamId, teamMap.getOrDefault(teamId, "Unknown"));
                })
                .collect(Collectors.toList());
    }

    @Transactional
    public DraftConfig resetDraft() {
        // Delete all picks and unmark players
        List<DraftPick> picks = draftPickRepository.findAll();
        picks.forEach(pick -> {
            pick.getPlayer().setDrafted(false);
            playerRepository.save(pick.getPlayer());
        });
        draftPickRepository.deleteAll();

        DraftConfig config = getConfig();
        config.setStatus(DraftStatus.NOT_STARTED);
        config.setCurrentPickNumber(0);
        return draftConfigRepository.save(config);
    }
}
