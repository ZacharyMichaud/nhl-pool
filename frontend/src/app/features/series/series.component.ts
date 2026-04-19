import { Component, inject, OnInit, signal } from '@angular/core';
import { CommonModule } from '@angular/common';
import { MatSnackBar, MatSnackBarModule } from '@angular/material/snack-bar';
import { catchError, forkJoin, of } from 'rxjs';
import { ApiService } from '../../core/api.service';
import { AuthService } from '../../core/auth.service';
import { DropdownComponent } from '../../shared/components/dropdown/dropdown.component';
import { DropdownOption } from '../../shared/components/dropdown/dropdown.types';

@Component({
  selector: 'app-series',
  standalone: true,
  imports: [CommonModule, MatSnackBarModule, DropdownComponent],
  templateUrl: './series.component.html',
  styleUrl: './series.component.scss',
})
export class SeriesComponent implements OnInit {
  private api      = inject(ApiService);
  private auth     = inject(AuthService);
  private snackBar = inject(MatSnackBar);

  predictionsLocked    = signal(false);
  selectedRound        = signal(1);
  series               = signal<any[]>([]);
  allTeams             = signal<any[]>([]);
  predictionDraft      = signal<Record<number, { winner: string; games: number } | undefined>>({});
  saving               = signal(false);
  allTeamPredictions   = signal<any[]>([]);

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
    }).subscribe(({ config, standings }) => {
      const locked = Boolean(config?.predictionsLocked);
      this.predictionsLocked.set(locked);
      this.allTeams.set(standings);
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

  getLogoForAbbrev(s: any, abbrev: string): string {
    if (!abbrev) return '';
    if (s.topSeedAbbrev === abbrev) return s.topSeedLogoUrl || '';
    if (s.bottomSeedAbbrev === abbrev) return s.bottomSeedLogoUrl || '';
    return '';
  }

  isMyTeam(teamId: number): boolean {
    return this.auth.teamId() === teamId;
  }
}
