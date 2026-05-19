import { Component, computed, inject, Input, OnChanges, Output, EventEmitter, signal } from '@angular/core';
import { CommonModule } from '@angular/common';
import { LiveGameService } from '../../../core/live-game.service';
import { AuthService } from '../../../core/auth.service';
import { DropdownComponent } from '../dropdown/dropdown.component';
import { DropdownOption } from '../dropdown/dropdown.types';
import { PoolBadgeComponent } from '../pool-badge/pool-badge.component';
import { PredPtsBadgeComponent } from '../pred-pts-badge/pred-pts-badge.component';

/**
 * Reusable list of series cards with sorting baked in.
 *
 * Sort order:
 *  1. Ongoing series (no winnerAbbrev) always above completed ones.
 *  2. Among ongoing: series with a live/critical game first, then by most-recently-played game (desc).
 *  3. Among completed: by most-recently-finished game (desc).
 */
@Component({
  selector: 'app-series-card-list',
  standalone: true,
  imports: [CommonModule, DropdownComponent, PoolBadgeComponent, PredPtsBadgeComponent],
  templateUrl: './series-card-list.component.html',
  styleUrl: './series-card-list.component.scss',
})
export class SeriesCardListComponent implements OnChanges {
  protected liveGame = inject(LiveGameService);
  protected auth     = inject(AuthService);

  /** The raw series list for this column/view. */
  @Input() series: any[] = [];

  /** Map of seriesId → game summaries. */
  @Input() seriesGames: Record<number, any[]> = {};

  /** Current prediction draft state. */
  @Input() predictionDraft: Record<number, { winner: string; games: number } | undefined> = {};

  /** All pool teams (for the picks list). */
  @Input() allTeams: any[] = [];

  /** All team predictions for the current round. */
  @Input() allTeamPredictions: any[] = [];

  /** Scoring rules per round number. */
  @Input() predScoringRules: any[] = [];

  /** Currently selected round number. */
  @Input() selectedRound: number = 1;

  @Output() winnerChange = new EventEmitter<{ seriesId: number; winner: string }>();
  @Output() gamesChange  = new EventEmitter<{ seriesId: number; games: number }>();

  readonly gameOptions: DropdownOption[] = [
    { value: 4, label: '4 Games' },
    { value: 5, label: '5 Games' },
    { value: 6, label: '6 Games' },
    { value: 7, label: '7 Games' },
  ];

  // Internal signal that mirrors the @Input so computed() can react to it.
  private _series   = signal<any[]>([]);
  private _games    = signal<Record<number, any[]>>({});

  ngOnChanges() {
    this._series.set(this.series ?? []);
    this._games.set(this.seriesGames ?? {});
  }

  // ── Sort ──────────────────────────────────────────────────────────────────

  readonly sortedSeries = computed(() => {
    const games = this._games(); // reactive dependency
    return [...this._series()].sort((a: any, b: any) => {
      const aOngoing = !a.winnerAbbrev;
      const bOngoing = !b.winnerAbbrev;

      // 1. Ongoing before completed
      if (aOngoing !== bOngoing) return aOngoing ? -1 : 1;

      if (aOngoing) {
        // 2a. Live game first
        const aLive = this.hasLiveGame(a.id);
        const bLive = this.hasLiveGame(b.id);
        if (aLive !== bLive) return aLive ? -1 : 1;

        // 2b. Most-recently played game date descending
        const aDate = this.getMostRecentGameDate(a.id) ?? '';
        const bDate = this.getMostRecentGameDate(b.id) ?? '';
        return bDate.localeCompare(aDate);
      }

      // 3. Both completed: most recently finished first
      const aDate = this.getMostRecentGameDate(a.id) ?? '';
      const bDate = this.getMostRecentGameDate(b.id) ?? '';
      return bDate.localeCompare(aDate);
    });
  });

  private getMostRecentGameDate(seriesId: number): string | null {
    const played = (this.seriesGames[seriesId] ?? [])
      .filter((g: any) => g.gameState !== 'PRE' && g.gameState !== 'FUT')
      .map((g: any) => g.gameDate as string)
      .filter(Boolean)
      .sort();
    return played.length > 0 ? played[played.length - 1] : null;
  }

  private hasLiveGame(seriesId: number): boolean {
    return (this.seriesGames[seriesId] ?? []).some(
      (g: any) => g.gameState === 'LIVE' || g.gameState === 'CRIT'
    );
  }

  // ── Helpers ───────────────────────────────────────────────────────────────

  isSeriesOpen(s: any): boolean { return !s.predictionsLocked; }
  isPicksRevealed(s: any): boolean { return !!s.predictionsLocked; }

  getGamesForSeries(seriesId: number): any[] { return this.seriesGames[seriesId] ?? []; }

  getNextGame(seriesId: number): any | null {
    return (this.seriesGames[seriesId] ?? [])
      .filter((g: any) => g.gameState === 'PRE' || g.gameState === 'FUT')
      .sort((a: any, b: any) => a.gameNumber - b.gameNumber)[0] ?? null;
  }

  getPlayedGames(seriesId: number): any[] {
    return (this.seriesGames[seriesId] ?? [])
      .filter((g: any) => g.gameState !== 'PRE' && g.gameState !== 'FUT');
  }

  formatGameDate(dateStr: string): string {
    if (!dateStr) return '';
    const [, m, d] = dateStr.split('-');
    return `${d}/${m}`;
  }

  getAlignedGameScore(game: any, series: any): [number, number] {
    const homeIsTop = game.homeAbbrev === series.topSeedAbbrev;
    return homeIsTop ? [game.homeScore, game.awayScore] : [game.awayScore, game.homeScore];
  }

  getGameSuffix(game: any): string {
    if (game.gameState === 'LIVE' || game.gameState === 'CRIT') return '';
    if (game.periodType === 'OT') return 'OT';
    if (game.periodType === 'SO') return 'SO';
    return '';
  }

  getLivePeriodLabel(game: any): string {
    if (game.periodType === 'OT') return 'OT';
    if (game.periodType === 'SO') return 'SO';
    return `${game.periodNumber}P`;
  }

  getGameWinnerAbbrev(game: any): string | null {
    const finished = game.gameState !== 'PRE' && game.gameState !== 'FUT'
                  && game.gameState !== 'LIVE' && game.gameState !== 'CRIT';
    if (!finished) return null;
    if (game.homeScore == null || game.awayScore == null) return null;
    return game.homeScore > game.awayScore ? game.homeAbbrev : game.awayAbbrev;
  }

  getWinnerOptions(s: any): DropdownOption[] {
    return [
      { value: s.topSeedAbbrev,    label: s.topSeedAbbrev },
      { value: s.bottomSeedAbbrev, label: s.bottomSeedAbbrev },
    ];
  }

  onWinnerChange(seriesId: number, winner: string) {
    this.winnerChange.emit({ seriesId, winner });
  }

  onGamesChange(seriesId: number, games: number) {
    this.gamesChange.emit({ seriesId, games });
  }

  getAllPredsForSeries(seriesId: number): { teamId: number; teamName: string; pred: any }[] {
    return this.allTeams.map(team => ({
      teamId:   team.teamId,
      teamName: team.teamName,
      pred: this.allTeamPredictions.find(
        (p: any) => p.series?.id === seriesId && p.team?.id === team.teamId
      ) ?? null,
    }));
  }

  teamColorIndex(teamId: number): number {
    return (teamId - 1) % 10;
  }

  isMyTeam(teamId: number): boolean { return this.auth.teamId() === teamId; }

  getLogoForAbbrev(s: any, abbrev: string): string {
    if (!abbrev) return '';
    if (s.topSeedAbbrev === abbrev) return s.topSeedLogoUrl || '';
    if (s.bottomSeedAbbrev === abbrev) return s.bottomSeedLogoUrl || '';
    return '';
  }

  getPredForSeries(seriesId: number): { winner: string; games: number } | undefined {
    return this.predictionDraft[seriesId];
  }

  getSeriesPoints(s: any, pred: any | null): { winnerPts: number; gamesPts: number; total: number } | null {
    if (!s.winnerAbbrev || !pred) return null;
    const roundNumber: number = s.round?.roundNumber ?? this.selectedRound;
    const rule = this.predScoringRules.find((r: any) => r.roundNumber === roundNumber);
    if (!rule) return null;
    const correctWinner = pred.predictedWinnerAbbrev === s.winnerAbbrev;
    if (!correctWinner) return { winnerPts: 0, gamesPts: 0, total: 0 };
    const winnerPts = rule.correctWinnerPoints ?? 0;
    const exactGames = pred.predictedGames === (s.topSeedWins + s.bottomSeedWins);
    const gamesPts = exactGames ? (rule.correctGamesBonus ?? 0) : 0;
    return { winnerPts, gamesPts, total: winnerPts + gamesPts };
  }
}
