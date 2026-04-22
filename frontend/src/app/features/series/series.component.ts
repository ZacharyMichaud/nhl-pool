import { Component, inject, OnInit, signal } from '@angular/core';
import { CommonModule } from '@angular/common';
import { MatSnackBar, MatSnackBarModule } from '@angular/material/snack-bar';
import { catchError, forkJoin, of } from 'rxjs';
import { ApiService } from '../../core/api.service';
import { AuthService } from '../../core/auth.service';
import { LiveGameService } from '../../core/live-game.service';
import { DropdownComponent } from '../../shared/components/dropdown/dropdown.component';
import { DropdownOption } from '../../shared/components/dropdown/dropdown.types';
import { PoolBadgeComponent } from '../../shared/components/pool-badge/pool-badge.component';

@Component({
  selector: 'app-series',
  standalone: true,
  imports: [CommonModule, MatSnackBarModule, DropdownComponent, PoolBadgeComponent],
  templateUrl: './series.component.html',
  styleUrl: './series.component.scss',
})
export class SeriesComponent implements OnInit {
  private api      = inject(ApiService);
  private auth     = inject(AuthService);
  private snackBar = inject(MatSnackBar);
  protected liveGame = inject(LiveGameService);

  predictionsLocked    = signal(false);
  selectedRound        = signal(1);
  series               = signal<any[]>([]);
  allTeams             = signal<any[]>([]);
  predictionDraft      = signal<Record<number, { winner: string; games: number } | undefined>>({});
  saving               = signal(false);
  allTeamPredictions   = signal<any[]>([]);
  predScoringRules     = signal<any[]>([]);
  seriesGames          = signal<Record<number, any[]>>({});  // seriesId → SeriesGameSummary[]

  readonly roundOptions = [
    { value: 1, label: 'Round 1' },
    { value: 2, label: 'Round 2' },
    { value: 3, label: 'Conf Finals' },
    { value: 4, label: 'Cup Final' },
  ];

  readonly roundDropdownOptions: DropdownOption[] = this.roundOptions.map(r => ({
    value: r.value,
    label: r.label,
  }));

  readonly gameOptions: DropdownOption[] = [
    { value: 4, label: '4 Games' },
    { value: 5, label: '5 Games' },
    { value: 6, label: '6 Games' },
    { value: 7, label: '7 Games' },
  ];

  ngOnInit() {
    forkJoin({
      config:    this.api.getDraftConfig().pipe(catchError(() => of(null))),
      standings: this.api.getStandings().pipe(catchError(() => of([]))),
      rules:     this.api.getPredictionScoringRules().pipe(catchError(() => of([]))),
    }).subscribe(({ config, standings, rules }) => {
      const locked = Boolean(config?.predictionsLocked);
      this.predictionsLocked.set(locked);
      this.allTeams.set(standings);
      this.predScoringRules.set(rules);
      this.loadRound(1, locked);
    });
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

  getNextGame(seriesId: number): any | null {
    const upcoming = (this.seriesGames()[seriesId] ?? [])
      .filter((g: any) => g.gameState === 'PRE' || g.gameState === 'FUT')
      .sort((a: any, b: any) => a.gameNumber - b.gameNumber);
    return upcoming[0] ?? null;
  }

  getPlayedGames(seriesId: number): any[] {
    return (this.seriesGames()[seriesId] ?? [])
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

  // ── Entry form helpers ─────────────────────────────────────────────────────
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

  get hasAnyPrediction(): boolean {
    return this.series().some(s => {
      const pred = this.predictionDraft()[s.id];
      return !s.winnerAbbrev && pred?.winner;
    });
  }

  saveAll() {
    if (this.saving()) return;
    const calls = this.series()
      .filter(s => !s.winnerAbbrev && this.predictionDraft()[s.id]?.winner)
      .map(s => {
        const pred = this.predictionDraft()[s.id]!;
        return this.api.submitPrediction(s.id, pred.winner, pred.games);
      });
    if (calls.length === 0) return;

    this.saving.set(true);
    forkJoin(calls).subscribe({
      next: () => {
        this.saving.set(false);
        this.snackBar.open('Predictions saved! 🎯', 'OK', { duration: 3000 });
      },
      error: (err) => {
        this.saving.set(false);
        this.snackBar.open(err.error?.error || 'Failed to save', 'OK', { duration: 4000 });
      },
    });
  }

  // ── Locked view helpers ────────────────────────────────────────────────────
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

  getLogoForAbbrev(s: any, abbrev: string): string {
    if (!abbrev) return '';
    if (s.topSeedAbbrev === abbrev) return s.topSeedLogoUrl || '';
    if (s.bottomSeedAbbrev === abbrev) return s.bottomSeedLogoUrl || '';
    return '';
  }

  isMyTeam(teamId: number): boolean {
    return this.auth.teamId() === teamId;
  }

  /** Returns earned prediction points for a finished series, or null if not over / no pred. */
  getSeriesPoints(s: any, pred: any | null): { winnerPts: number; gamesPts: number; total: number } | null {
    if (!s.winnerAbbrev || !pred) return null;
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
