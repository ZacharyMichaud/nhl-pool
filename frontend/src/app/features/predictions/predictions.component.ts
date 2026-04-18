import { Component, inject, OnInit, signal } from '@angular/core';
import { CommonModule } from '@angular/common';
import { MatSnackBar, MatSnackBarModule } from '@angular/material/snack-bar';
import { forkJoin, of } from 'rxjs';
import { ApiService } from '../../core/api.service';
import { AuthService } from '../../core/auth.service';
import { DropdownComponent } from '../../shared/components/dropdown/dropdown.component';
import { DropdownOption } from '../../shared/components/dropdown/dropdown.types';

@Component({
  selector: 'app-predictions',
  standalone: true,
  imports: [CommonModule, MatSnackBarModule, DropdownComponent],
  templateUrl: './predictions.component.html',
  styleUrl: './predictions.component.scss',
})
export class PredictionsComponent implements OnInit {
  private api = inject(ApiService);
  private auth = inject(AuthService);
  private snackBar = inject(MatSnackBar);

  series = signal<any[]>([]);
  predictions = signal<Record<number, { winner: string; games: number } | undefined>>({});
  selectedRound = 1;
  saving = signal(false);

  roundOptions = [
    { value: 1, label: 'Round 1' },
    { value: 2, label: 'Round 2' },
    { value: 3, label: 'Conf Finals' },
    { value: 4, label: 'Cup Final' },
  ];

  gameOptions: DropdownOption[] = [
    { value: 4, label: '4 Games' },
    { value: 5, label: '5 Games' },
    { value: 6, label: '6 Games' },
    { value: 7, label: '7 Games' },
  ];

  ngOnInit() {
    this.selectRound(1);
  }

  selectRound(round: number) {
    this.selectedRound = round;

    // Fetch series and existing predictions in parallel
    forkJoin({
      seriesList: this.api.getSeries(round),
      savedPreds: this.api.getPredictions(round),
    }).subscribe(({ seriesList, savedPreds }) => {
      this.series.set(seriesList);

      // Build a map of seriesId → saved prediction
      const savedMap: Record<number, { winner: string; games: number }> = {};
      savedPreds.forEach((p: any) => {
        savedMap[p.series.id] = {
          winner: p.predictedWinnerAbbrev,
          games: p.predictedGames,
        };
      });

      // Initialize predictions: use saved value if available, otherwise blank
      const preds: Record<number, { winner: string; games: number }> = {};
      seriesList.forEach((s: any) => {
        preds[s.id] = savedMap[s.id] ?? { winner: '', games: 4 };
      });
      this.predictions.set(preds);
    });
  }

  getWinnerOptions(s: any): DropdownOption[] {
    return [
      { value: s.topSeedAbbrev, label: s.topSeedAbbrev },
      { value: s.bottomSeedAbbrev, label: s.bottomSeedAbbrev },
    ];
  }

  onWinnerChange(seriesId: number, winner: string) {
    const current = this.predictions();
    this.predictions.set({ ...current, [seriesId]: { ...(current[seriesId] ?? { winner: '', games: 4 }), winner } });
  }

  onGamesChange(seriesId: number, games: number) {
    const current = this.predictions();
    this.predictions.set({ ...current, [seriesId]: { ...(current[seriesId] ?? { winner: '', games: 4 }), games } });
  }

  get hasAnyPrediction(): boolean {
    return this.series().some(s => {
      const pred = this.predictions()[s.id];
      return !s.winnerAbbrev && pred?.winner;
    });
  }

  saveAll() {
    if (this.saving()) return;

    const calls = this.series()
      .filter(s => !s.winnerAbbrev && this.predictions()[s.id]?.winner)
      .map(s => {
        const pred = this.predictions()[s.id]!;
        return this.api.submitPrediction(s.id, pred.winner, pred.games);
      });

    if (calls.length === 0) return;

    this.saving.set(true);
    forkJoin(calls).subscribe({
      next: () => {
        this.saving.set(false);
        this.snackBar.open('All predictions saved!', 'OK', { duration: 3000 });
      },
      error: (err) => {
        this.saving.set(false);
        this.snackBar.open(err.error?.error || 'Failed to save', 'OK', { duration: 4000 });
      },
    });
  }
}
