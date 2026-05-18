import { Component, computed, inject, OnDestroy, OnInit, signal } from '@angular/core';
import { CommonModule } from '@angular/common';
import { Router } from '@angular/router';
import { catchError, forkJoin, of, Subscription } from 'rxjs';
import { ApiService } from '../../core/api.service';
import { AuthService } from '../../core/auth.service';
import { DraftEventService } from '../../core/draft-event.service';
import { LiveGameService } from '../../core/live-game.service';
import { MatSnackBar, MatSnackBarModule } from '@angular/material/snack-bar';
import { DropdownComponent } from '../../shared/components/dropdown/dropdown.component';
import { DropdownOption } from '../../shared/components/dropdown/dropdown.types';
import { PoolBadgeComponent } from '../../shared/components/pool-badge/pool-badge.component';
import { SeriesCardListComponent } from '../../shared/components/series-card-list/series-card-list.component';

// Map of NHL team abbreviations to their conference.
// Series letter codes change every round (A-H in R1, I-L in R2, M-N in CF),
// so we derive the conference from the topSeed team instead.
const WESTERN_TEAMS = new Set([
  'ANA', 'ARI', 'CGY', 'CHI', 'COL', 'DAL', 'EDM', 'LAK',
  'MIN', 'NSH', 'SJS', 'SEA', 'STL', 'UTA', 'VAN', 'VGK', 'WPG',
]);

@Component({
  selector: 'app-standings',
  standalone: true,
  imports: [CommonModule, MatSnackBarModule, DropdownComponent, PoolBadgeComponent, SeriesCardListComponent],
  templateUrl: './standings.component.html',
  styleUrl: './standings.component.scss',
})
export class StandingsComponent implements OnInit, OnDestroy {
  private api    = inject(ApiService);
  private router = inject(Router);
  protected auth = inject(AuthService);
  private draftEvent = inject(DraftEventService);
  protected liveGame = inject(LiveGameService);
  private snackBar   = inject(MatSnackBar);
  private statsSub?: Subscription;

  standings         = signal<any[]>([]);
  series            = signal<any[]>([]);
  selectedRound     = signal(1);
  allTeams          = signal<any[]>([]);
  allTeamPredictions = signal<any[]>([]);
  predScoringRules  = signal<any[]>([]);  // PredictionScoringRule[]
  seriesGames       = signal<Record<number, any[]>>({});  // seriesId → SeriesGameSummary[]
  saving            = signal(false);
  predictionDraft   = signal<Record<number, { winner: string; games: number } | undefined>>({});
  /** Snapshot of predictions as last loaded/saved — drives the save button visibility. */
  savedDraft        = signal<Record<number, { winner: string; games: number } | undefined>>({});

  readonly roundDropdownOptions: DropdownOption[] = [
    { value: 1, label: 'Round 1' },
    { value: 2, label: 'Round 2' },
    { value: 3, label: 'Conf Finals' },
    { value: 4, label: 'Cup Final' },
  ];

  readonly gameOptions: DropdownOption[] = [
    { value: 4, label: '4 Games' },
    { value: 5, label: '5 Games' },
    { value: 6, label: '6 Games' },
    { value: 7, label: '7 Games' },
  ];

  // ── Conference split (raw) ────────────────────────────────────────────────
  private _westSeries = computed(() => this.series().filter((s: any) =>
    WESTERN_TEAMS.has(s.topSeedAbbrev?.toUpperCase()) || WESTERN_TEAMS.has(s.bottomSeedAbbrev?.toUpperCase())
  ));
  private _eastSeries = computed(() => this.series().filter((s: any) =>
    !WESTERN_TEAMS.has(s.topSeedAbbrev?.toUpperCase()) && !WESTERN_TEAMS.has(s.bottomSeedAbbrev?.toUpperCase())
  ));

  // ── Sort helpers ──────────────────────────────────────────────────────────
  private getMostRecentGameDate(seriesId: number): string | null {
    const played = (this.seriesGames()[seriesId] ?? [])
      .filter((g: any) => g.gameState !== 'PRE' && g.gameState !== 'FUT')
      .map((g: any) => g.gameDate as string)
      .filter(Boolean)
      .sort();
    return played.length > 0 ? played[played.length - 1] : null;
  }

  private hasLiveGame(seriesId: number): boolean {
    return (this.seriesGames()[seriesId] ?? []).some(
      (g: any) => g.gameState === 'LIVE' || g.gameState === 'CRIT'
    );
  }

  private sortSeriesList(list: any[]): any[] {
    return [...list].sort((a: any, b: any) => {
      const aOngoing = !a.winnerAbbrev;
      const bOngoing = !b.winnerAbbrev;
      if (aOngoing !== bOngoing) return aOngoing ? -1 : 1;
      if (aOngoing) {
        const aLive = this.hasLiveGame(a.id);
        const bLive = this.hasLiveGame(b.id);
        if (aLive !== bLive) return aLive ? -1 : 1;
        const aDate = this.getMostRecentGameDate(a.id) ?? '';
        const bDate = this.getMostRecentGameDate(b.id) ?? '';
        return bDate.localeCompare(aDate);
      }
      const aDate = this.getMostRecentGameDate(a.id) ?? '';
      const bDate = this.getMostRecentGameDate(b.id) ?? '';
      return bDate.localeCompare(aDate);
    });
  }

  // Sorted conference lists exposed to the template
  westSeries = computed(() => this.sortSeriesList(this._westSeries()));
  eastSeries = computed(() => this.sortSeriesList(this._eastSeries()));

  ngOnInit() {
    this.draftEvent.connect();
    forkJoin({
      standings:  this.api.getStandings().pipe(catchError(() => of([]))),
      rules:      this.api.getPredictionScoringRules().pipe(catchError(() => of([]))),
      rounds:     this.api.getPublicRounds().pipe(catchError(() => of([]))),
      allSeries:  this.api.getAllSeries().pipe(catchError(() => of([]))),
    }).subscribe(({ standings, rules, rounds, allSeries }) => {
      this.standings.set(standings);
      this.allTeams.set(standings);
      this.predScoringRules.set(rules);

      const defaultRound = this.computeDefaultRound(rounds, allSeries);
      this.loadRound(defaultRound);
    });

    // Reload standings whenever the backend broadcasts a stats update
    this.statsSub = this.draftEvent.statsUpdated$.subscribe(() => {
      this.api.getStandings().pipe(catchError(() => of([]))).subscribe(standings => {
        this.standings.set(standings);
        this.allTeams.set(standings);
      });
    });
  }

  /**
   * Returns the round number to default to.
   *
   * Priority:
   *  1. Lowest round with at least one incomplete series (no winner yet) — driven by real game data.
   *     Math.min ensures round 2 (G7 tomorrow) beats round 3 (confirmed but not started).
   *  2. Highest round whose PoolRound.status is ACTIVE.
   *  3. Highest round whose PoolRound.status is COMPLETED.
   *  4. 1 (safe fallback).
   */
  private computeDefaultRound(rounds: any[], allSeries: any[]): number {
    if (allSeries && allSeries.length > 0) {
      const incompleteRounds = allSeries
        .filter((s: any) => !s.winnerAbbrev)
        .map((s: any) => s.round?.roundNumber as number)
        .filter((rn: number) => !!rn);
      if (incompleteRounds.length > 0) {
        return Math.min(...incompleteRounds);
      }
    }

    if (rounds && rounds.length > 0) {
      const active = rounds
        .filter((r: any) => r.status === 'ACTIVE')
        .sort((a: any, b: any) => b.roundNumber - a.roundNumber);
      if (active.length > 0) return active[0].roundNumber;

      const completed = rounds
        .filter((r: any) => r.status === 'COMPLETED')
        .sort((a: any, b: any) => b.roundNumber - a.roundNumber);
      if (completed.length > 0) return completed[0].roundNumber;
    }

    return 1;
  }

  ngOnDestroy() {
    this.statsSub?.unsubscribe();
  }

  /** A series is "open" for predictions when the admin hasn't locked it. */
  isSeriesOpen(s: any): boolean {
    return !s.predictionsLocked;
  }

  /** Other teams' picks are visible when the series is locked. */
  isPicksRevealed(s: any): boolean {
    return !!s.predictionsLocked;
  }

  selectRound(round: number) {
    this.selectedRound.set(round);
    this.loadRound(round);
  }

  private loadRound(round: number) {
    this.selectedRound.set(round);

    forkJoin({
      series:     this.api.getSeries(round),
      savedPreds: this.api.getPredictions(round).pipe(catchError(() => of([]))),
      allPreds:   this.api.getAllTeamsPredictions(round).pipe(catchError(() => of([]))),
    }).subscribe(({ series, savedPreds, allPreds }) => {
      this.series.set(series);
      this.allTeamPredictions.set(allPreds);
      this.loadSeriesGames(series);

      const savedMap: Record<number, { winner: string; games: number }> = {};
      savedPreds.forEach((p: any) => {
        savedMap[p.series.id] = { winner: p.predictedWinnerAbbrev, games: p.predictedGames };
      });
      const draft: Record<number, { winner: string; games: number }> = {};
      series.forEach((s: any) => {
        draft[s.id] = savedMap[s.id] ?? { winner: '', games: 4 };
      });
      this.predictionDraft.set(draft);
      this.savedDraft.set(JSON.parse(JSON.stringify(draft)));
    });
  }

  private loadSeriesGames(seriesList: any[]) {
    seriesList.forEach((s: any) => {
      this.api.getSeriesGames(s.id).pipe(catchError(() => of([]))).subscribe((games: any[]) => {
        this.seriesGames.update(map => ({ ...map, [s.id]: games }));
      });
    });
  }

  getGamesForSeries(seriesId: number): any[] {
    return this.seriesGames()[seriesId] ?? [];
  }

  /** Returns the next scheduled (PRE/FUT) game for a series, or null if none. */
  getNextGame(seriesId: number): any | null {
    const upcoming = (this.seriesGames()[seriesId] ?? [])
      .filter((g: any) => g.gameState === 'PRE' || g.gameState === 'FUT')
      .sort((a: any, b: any) => a.gameNumber - b.gameNumber);
    return upcoming[0] ?? null;
  }

  /** Returns only finished/live games (excludes PRE/FUT) for the history list. */
  getPlayedGames(seriesId: number): any[] {
    return (this.seriesGames()[seriesId] ?? [])
      .filter((g: any) => g.gameState !== 'PRE' && g.gameState !== 'FUT');
  }
  /** Formats an ISO date string (yyyy-MM-dd) to DD/MM for compact display. */
  formatGameDate(dateStr: string): string {
    if (!dateStr) return '';
    const [, m, d] = dateStr.split('-');
    return `${d}/${m}`;
  }

  /**
   * Returns [topSeedScore, bottomSeedScore] for a game, so the score always
   * aligns with the series card (top seed on left, bottom seed on right).
   */
  getAlignedGameScore(game: any, series: any): [number, number] {
    const homeIsTop = game.homeAbbrev === series.topSeedAbbrev;
    return homeIsTop
      ? [game.homeScore, game.awayScore]
      : [game.awayScore, game.homeScore];
  }

  /** Returns the OT suffix for a finished game, empty string for regulation. (No SO in playoffs.) */
  getGameSuffix(game: any): string {
    if (game.gameState === 'LIVE' || game.gameState === 'CRIT') return '';
    if (game.periodType === 'OT') return 'OT';
    return '';
  }

  /** Returns a compact period label for live games, e.g. '2P', 'OT'. (No SO in playoffs.) */
  getLivePeriodLabel(game: any): string {
    if (game.periodType === 'OT') return 'OT';
    return `${game.periodNumber}P`;
  }

  /** Returns the abbreviation of the game winner for a completed game, null otherwise. */
  getGameWinnerAbbrev(game: any): string | null {
    const finished = game.gameState !== 'PRE' && game.gameState !== 'FUT'
                  && game.gameState !== 'LIVE' && game.gameState !== 'CRIT';
    if (!finished) return null;
    if (game.homeScore == null || game.awayScore == null) return null;
    return game.homeScore > game.awayScore ? game.homeAbbrev : game.awayAbbrev;
  }

  isMyTeam(teamId: number): boolean {
    return this.auth.teamId() === teamId;
  }

  goToTeam(teamId: number) {
    this.router.navigate(['/teams'], { queryParams: { teamId } });
  }

  // ── Series helpers ──────────────────────────────────────────────────────────
  getWinnerOptions(s: any): DropdownOption[] {
    return [
      { value: s.topSeedAbbrev,    label: s.topSeedAbbrev },
      { value: s.bottomSeedAbbrev, label: s.bottomSeedAbbrev },
    ];
  }

  /**
   * True when the current draft differs from the last-saved state for at least
   * one open (unlocked) series. Drives the visibility of the save button.
   */
  get hasDirtyPredictions(): boolean {
    const current = this.predictionDraft();
    const saved   = this.savedDraft();
    return this.series().some(s => {
      if (s.predictionsLocked) return false;
      const cur = current[s.id];
      const sav = saved[s.id];
      return cur?.winner !== sav?.winner || cur?.games !== sav?.games;
    });
  }

  saveAll() {
    if (this.saving()) return;
    const draft = this.predictionDraft();
    const calls = this.series()
      .filter(s => !s.predictionsLocked && draft[s.id]?.winner)
      .map(s => {
        const pred = draft[s.id]!;
        return this.api.submitPrediction(s.id, pred.winner, pred.games);
      });
    if (calls.length === 0) return;

    this.saving.set(true);
    forkJoin(calls).subscribe({
      next: () => {
        this.saving.set(false);
        this.savedDraft.set(JSON.parse(JSON.stringify(this.predictionDraft())));
        this.snackBar.open('Predictions saved! 🎯', 'OK', { duration: 3000 });
      },
      error: (err) => {
        this.saving.set(false);
        this.snackBar.open(err.error?.error || 'Failed to save', 'OK', { duration: 4000 });
      },
    });
  }

  onWinnerChange(event: { seriesId: number; winner: string }) {
    const cur = this.predictionDraft();
    this.predictionDraft.set({ ...cur, [event.seriesId]: { ...(cur[event.seriesId] ?? { winner: '', games: 4 }), winner: event.winner } });
  }

  onGamesChange(event: { seriesId: number; games: number }) {
    const cur = this.predictionDraft();
    this.predictionDraft.set({ ...cur, [event.seriesId]: { ...(cur[event.seriesId] ?? { winner: '', games: 4 }), games: event.games } });
  }

  getAllPredsForSeries(seriesId: number): { teamId: number; teamName: string; pred: any }[] {
    return this.allTeams().map(team => ({
      teamId:   team.teamId,
      teamName: team.teamName,
      pred: this.allTeamPredictions().find(
        (p: any) => p.series?.id === seriesId && p.team?.id === team.teamId
      ) ?? null,
    }));
  }

  /** Returns a stable color index (0–9) for each pool team, matching the badge palette. */
  teamColorIndex(teamId: number): number {
    return (teamId - 1) % 10;
  }

  /**
   * Returns the badge-palette hex colour for the 0-based standings index `i`.
   * Mirrors the $colors list used by PoolBadgeComponent.
   */
  private static readonly BADGE_COLORS = [
    '#00c3ff', '#ff6b6b', '#ffd166', '#06d6a0', '#a78bfa',
    '#fb923c', '#f472b6', '#34d399', '#60a5fa', '#fbbf24',
  ];

  rankColor(teamId: number): string {
    return StandingsComponent.BADGE_COLORS[(teamId - 1) % 10];
  }

  /** Converts a '#rrggbb' hex string to 'r, g, b' for use in rgba() expressions. */
  hexToRgb(hex: string): string {
    const r = parseInt(hex.slice(1, 3), 16);
    const g = parseInt(hex.slice(3, 5), 16);
    const b = parseInt(hex.slice(5, 7), 16);
    return `${r}, ${g}, ${b}`;
  }

  getLogoForAbbrev(s: any, abbrev: string): string {
    if (!abbrev) return '';
    if (s.topSeedAbbrev === abbrev) return s.topSeedLogoUrl || '';
    if (s.bottomSeedAbbrev === abbrev) return s.bottomSeedLogoUrl || '';
    return '';
  }

  /**
   * Returns how many prediction points a pool team earned (or would earn if still live)
   * for a given series + prediction entry.
   * Returns null when the series isn't finished yet.
   */
  getSeriesPoints(s: any, pred: any | null): { winnerPts: number; gamesPts: number; total: number } | null {
    if (!s.winnerAbbrev || !pred) return null;  // series not over or no prediction
    const roundNumber: number = s.round?.roundNumber ?? this.selectedRound();
    const rule = this.predScoringRules().find((r: any) => r.roundNumber === roundNumber);
    if (!rule) return null;
    const correctWinner = pred.predictedWinnerAbbrev === s.winnerAbbrev;
    if (!correctWinner) return { winnerPts: 0, gamesPts: 0, total: 0 };
    const winnerPts = rule.correctWinnerPoints ?? 0;
    const exactGames = pred.predictedGames === (s.topSeedWins + s.bottomSeedWins);
    const gamesPts = exactGames ? (rule.correctGamesBonus ?? 0) : 0;
    return { winnerPts, gamesPts, total: winnerPts + gamesPts };
  }
}
