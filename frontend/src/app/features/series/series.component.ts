import { Component, inject, OnInit, signal } from '@angular/core';
import { CommonModule } from '@angular/common';
import { MatSnackBar, MatSnackBarModule } from '@angular/material/snack-bar';
import { catchError, forkJoin, of } from 'rxjs';
import { ApiService } from '../../core/api.service';
import { DropdownComponent } from '../../shared/components/dropdown/dropdown.component';
import { DropdownOption } from '../../shared/components/dropdown/dropdown.types';
import { SeriesCardListComponent } from '../../shared/components/series-card-list/series-card-list.component';

@Component({
  selector: 'app-series',
  standalone: true,
  imports: [CommonModule, MatSnackBarModule, DropdownComponent, SeriesCardListComponent],
  templateUrl: './series.component.html',
  styleUrl: './series.component.scss',
})
export class SeriesComponent implements OnInit {
  private api      = inject(ApiService);
  private snackBar = inject(MatSnackBar);

  selectedRound        = signal(1);
  series               = signal<any[]>([]);
  allTeams             = signal<any[]>([]);
  predictionDraft      = signal<Record<number, { winner: string; games: number } | undefined>>({});
  /** Snapshot of predictions as last loaded / saved — used to detect unsaved changes. */
  savedDraft           = signal<Record<number, { winner: string; games: number } | undefined>>({});
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

  ngOnInit() {
    forkJoin({
      standings:  this.api.getStandings().pipe(catchError(() => of([]))),
      rules:      this.api.getPredictionScoringRules().pipe(catchError(() => of([]))),
      rounds:     this.api.getPublicRounds().pipe(catchError(() => of([]))),
      allSeries:  this.api.getAllSeries().pipe(catchError(() => of([]))),
    }).subscribe(({ standings, rules, rounds, allSeries }) => {
      this.allTeams.set(standings);
      this.predScoringRules.set(rules);

      const defaultRound = this.computeDefaultRound(rounds, allSeries);
      this.loadRound(defaultRound);
    });
  }

  /**
   * Returns the round number to default to.
   *
   * Priority:
   *  1. Highest round that has at least one active (incomplete) series — winner not yet decided.
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

  // ── Prediction draft mutations (delegated from child) ──────────────────────

  onWinnerChange(event: { seriesId: number; winner: string }) {
    const cur = this.predictionDraft();
    this.predictionDraft.set({
      ...cur,
      [event.seriesId]: { ...(cur[event.seriesId] ?? { winner: '', games: 4 }), winner: event.winner },
    });
  }

  onGamesChange(event: { seriesId: number; games: number }) {
    const cur = this.predictionDraft();
    this.predictionDraft.set({
      ...cur,
      [event.seriesId]: { ...(cur[event.seriesId] ?? { winner: '', games: 4 }), games: event.games },
    });
  }

  /**
   * True when the current draft differs from the last-saved state for at least
   * one open series.  Drives the visibility of the Save button.
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
}
