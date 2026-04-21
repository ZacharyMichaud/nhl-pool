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
      });
    } else {
      forkJoin({
        series:     this.api.getSeries(round),
        savedPreds: this.api.getPredictions(round).pipe(catchError(() => of([]))),
      }).subscribe(({ series, savedPreds }) => {
        this.series.set(series);
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
