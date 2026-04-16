import { Component, inject, OnInit, signal } from '@angular/core';
import { CommonModule } from '@angular/common';
import { FormsModule } from '@angular/forms';
import { MatSelectModule } from '@angular/material/select';
import { MatButtonModule } from '@angular/material/button';
import { MatSnackBar, MatSnackBarModule } from '@angular/material/snack-bar';
import { MatFormFieldModule } from '@angular/material/form-field';
import { ApiService } from '../../core/api.service';
import { AuthService } from '../../core/auth.service';

@Component({
  selector: 'app-predictions',
  standalone: true,
  imports: [CommonModule, FormsModule, MatSelectModule, MatButtonModule, MatSnackBarModule, MatFormFieldModule],
  templateUrl: './predictions.component.html',
  styleUrl: './predictions.component.scss',
})
export class PredictionsComponent implements OnInit {
  private api = inject(ApiService);
  private auth = inject(AuthService);
  private snackBar = inject(MatSnackBar);

  series = signal<any[]>([]);
  predictions = signal<Record<number, { winner: string; games: number }>>({});
  selectedRound = 1;

  roundOptions = [
    { value: 1, label: 'Round 1' },
    { value: 2, label: 'Round 2' },
    { value: 3, label: 'Conf Finals' },
    { value: 4, label: 'Cup Final' },
  ];

  ngOnInit() {
    this.selectRound(1);
  }

  selectRound(round: number) {
    this.selectedRound = round;
    this.api.getSeries(round).subscribe((s) => {
      this.series.set(s);
      const preds: Record<number, any> = {};
      s.forEach((ser: any) => (preds[ser.id] = { winner: '', games: 4 }));
      this.predictions.set(preds);
    });
  }

  submitPrediction(s: any) {
    const pred = this.predictions()[s.id];
    if (!pred?.winner) return;
    this.api.submitPrediction(s.id, pred.winner, pred.games).subscribe({
      next: () => this.snackBar.open('Prediction saved!', 'OK', { duration: 3000 }),
      error: (err) => this.snackBar.open(err.error?.error || 'Failed', 'OK', { duration: 4000 }),
    });
  }
}
