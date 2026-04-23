import { Component, computed, inject, OnDestroy, OnInit, signal } from '@angular/core';
import { CommonModule } from '@angular/common';
import { Router } from '@angular/router';
import { catchError, forkJoin, of, Subscription } from 'rxjs';
import { ApiService } from '../../core/api.service';
import { AuthService } from '../../core/auth.service';
import { DraftEventService } from '../../core/draft-event.service';
import { LiveGameService } from '../../core/live-game.service';
import { DropdownComponent } from '../../shared/components/dropdown/dropdown.component';
import { DropdownOption } from '../../shared/components/dropdown/dropdown.types';
import { PoolBadgeComponent } from '../../shared/components/pool-badge/pool-badge.component';

// NHL API assigns series letters — flip if Western shows on the right
const WEST_CODES = new Set(['E', 'F', 'G', 'H']);

@Component({
  selector: 'app-standings',
  standalone: true,
  imports: [CommonModule, DropdownComponent, PoolBadgeComponent],
  templateUrl: './standings.component.html',
  styleUrl: './standings.component.scss',
})
export class StandingsComponent implements OnInit, OnDestroy {
  private api    = inject(ApiService);
  private router = inject(Router);
  protected auth = inject(AuthService);
  private draftEvent = inject(DraftEventService);
  protected liveGame = inject(LiveGameService);
  private statsSub?: Subscription;

  standings         = signal<any[]>([]);
  series            = signal<any[]>([]);
  selectedRound     = signal(1);
  predictionsLocked = signal(false);
  allTeams          = signal<any[]>([]);
  allTeamPredictions = signal<any[]>([]);
  predScoringRules  = signal<any[]>([]);  // PredictionScoringRule[]
  seriesGames       = signal<Record<number, any[]>>({});  // seriesId → SeriesGameSummary[]
  saving            = signal(false);
  predictionDraft   = signal<Record<number, { winner: string; games: number } | undefined>>({});

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

  westSeries = computed(() => this.series().filter(s => WEST_CODES.has(s.seriesCode?.toUpperCase())));
  eastSeries = computed(() => this.series().filter(s => !WEST_CODES.has(s.seriesCode?.toUpperCase())));

  ngOnInit() {
    this.draftEvent.connect();
    forkJoin({
      standings: this.api.getStandings().pipe(catchError(() => of([]))),
      config:    this.api.getDraftConfig().pipe(catchError(() => of(null))),
      rules:     this.api.getPredictionScoringRules().pipe(catchError(() => of([]))),
    }).subscribe(({ standings, config, rules }) => {
      this.standings.set(standings);
      this.allTeams.set(standings);
      this.predScoringRules.set(rules);
      const locked = Boolean(config?.predictionsLocked);
      this.predictionsLocked.set(locked);
      this.loadRound(1, locked);
    });

    // Reload standings whenever the backend broadcasts a stats update
    this.statsSub = this.draftEvent.statsUpdated$.subscribe(() => {
      this.api.getStandings().pipe(catchError(() => of([]))).subscribe(standings => {
        this.standings.set(standings);
        this.allTeams.set(standings);
      });
    });
  }

  ngOnDestroy() {
    this.statsSub?.unsubscribe();
  }

  selectRound(round: number) {
    this.selectedRound.set(round);
    this.loadRound(round, this.predictionsLocked());
  }

  private loadRound(round: number, locked: boolean) {
    this.selectedRound.set(round);
    if (locked) {
      forkJoin({
        series: this.api.getSeries(round),
        preds:  this.api.getAllTeamsPredictions(round),
      }).subscribe(({ series, preds }) => {
        this.series.set(series);
        this.allTeamPredictions.set(preds);
        this.loadSeriesGames(series);
      });
    } else {
      forkJoin({
        series:     this.api.getSeries(round),
        savedPreds: this.api.getPredictions(round).pipe(catchError(() => of([]))),
      }).subscribe(({ series, savedPreds }) => {
        this.series.set(series);
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
      });
    }
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

  onWinnerChange(seriesId: number, winner: string) {
    const cur = this.predictionDraft();
    this.predictionDraft.set({ ...cur, [seriesId]: { ...(cur[seriesId] ?? { winner: '', games: 4 }), winner } });
  }

  onGamesChange(seriesId: number, games: number) {
    const cur = this.predictionDraft();
    this.predictionDraft.set({ ...cur, [seriesId]: { ...(cur[seriesId] ?? { winner: '', games: 4 }), games } });
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
    const idx = this.allTeams().findIndex((t: any) => t.teamId === teamId);
    return idx === -1 ? 0 : idx % 10;
  }

  /**
   * Returns the badge-palette hex colour for the 0-based standings index `i`.
   * Mirrors the $colors list used by PoolBadgeComponent.
   */
  private static readonly BADGE_COLORS = [
    '#00c3ff', '#ff6b6b', '#ffd166', '#06d6a0', '#a78bfa',
    '#fb923c', '#f472b6', '#34d399', '#60a5fa', '#fbbf24',
  ];

  rankColor(i: number): string {
    return StandingsComponent.BADGE_COLORS[i % 10];
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
