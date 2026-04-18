import { Component, computed, ElementRef, inject, OnDestroy, OnInit, signal } from '@angular/core';
import { CommonModule } from '@angular/common';
import { FormsModule } from '@angular/forms';
import { debounceTime, distinctUntilChanged, forkJoin, Subject, switchMap } from 'rxjs';
import { ApiService } from '../../core/api.service';
import { AuthService } from '../../core/auth.service';
import { DropdownComponent } from '../../shared/components/dropdown/dropdown.component';
import { DropdownOption } from '../../shared/components/dropdown/dropdown.types';
import { MatSnackBar, MatSnackBarModule } from '@angular/material/snack-bar';

@Component({
  selector: 'app-my-team',
  standalone: true,
  imports: [CommonModule, FormsModule, DropdownComponent, MatSnackBarModule],
  templateUrl: './my-team.component.html',
  styleUrl: './my-team.component.scss',
})
export class MyTeamComponent implements OnInit, OnDestroy {
  private api       = inject(ApiService);
  private el        = inject(ElementRef);
  private snackBar  = inject(MatSnackBar);
  protected auth    = inject(AuthService);

  allTeams       = signal<any[]>([]);
  selectedTeamId = signal<number | null>(null);

  // Predictions lock + all-team predictions state
  predictionsLocked  = signal(false);
  selectedPredRound  = signal(1);
  allTeamPredictions = signal<any[]>([]);
  seriesForRound     = signal<any[]>([]);

  readonly roundOptions = [
    { value: 1, label: 'Round 1' },
    { value: 2, label: 'Round 2' },
    { value: 3, label: 'Conf Finals' },
    { value: 4, label: 'Cup Final' },
  ];

  // Conn Smythe search state (only for the user's own team edit)
  csSearch       = signal('');
  csResults      = signal<any[]>([]);
  csSaving       = signal(false);
  csShowDropdown = signal(false);

  private searchSubject = new Subject<string>();

  teamOptions = computed<DropdownOption[]>(() =>
    this.allTeams().map(t => ({
      value:    t.teamId,
      label:    t.teamName,
      sublabel: `${t.totalPoints} pts`,
      badge:    this.auth.teamId() === t.teamId ? 'You' : undefined,
    }))
  );

  selectedTeam = computed(() =>
    this.allTeams().find((t: any) => t.teamId === this.selectedTeamId()) ?? null
  );

  /** Predictions for the currently selected team in the selected round */
  selectedTeamPredictions = computed(() => {
    const teamId = this.selectedTeamId();
    return this.allTeamPredictions().filter((p: any) => p.team?.id === teamId);
  });

  private outsideClickHandler = (e: MouseEvent) => {
    if (!this.el.nativeElement.contains(e.target)) { /* handled inside component */ }
  };

  ngOnInit() {
    this.loadTeams();

    // Debounced player search
    this.searchSubject.pipe(
      debounceTime(300),
      distinctUntilChanged(),
      switchMap(q => q.trim().length >= 2 ? this.api.searchPlayers(q) : [])
    ).subscribe(results => {
      this.csResults.set(results);
      this.csShowDropdown.set(results.length > 0);
    });
  }

  ngOnDestroy() {}

  loadTeams() {
    forkJoin({
      standings: this.api.getStandings(),
      config: this.api.getDraftConfig(),
    }).subscribe(({ standings, config }) => {
      this.allTeams.set(standings);
      const myId = this.auth.teamId();
      const mine = standings.find((s: any) => s.teamId === myId);
      this.selectedTeamId.set(mine?.teamId ?? standings[0]?.teamId ?? null);

      // Pre-fill the search box with the current CS pick for the user's team
      if (mine?.connSmythePlayerName) {
        this.csSearch.set(mine.connSmythePlayerName);
      }

      const locked = Boolean(config?.predictionsLocked);
      this.predictionsLocked.set(locked);

      if (locked) {
        this.loadPredictionsForRound(this.selectedPredRound());
      }
    });
  }

  selectRound(round: number) {
    this.selectedPredRound.set(round);
    if (this.predictionsLocked()) {
      this.loadPredictionsForRound(round);
    }
  }

  private loadPredictionsForRound(round: number) {
    forkJoin({
      preds: this.api.getAllTeamsPredictions(round),
      series: this.api.getSeries(round),
    }).subscribe(({ preds, series }) => {
      this.allTeamPredictions.set(preds);
      this.seriesForRound.set(series);
    });
  }

  selectTeam(teamId: number) {
    this.selectedTeamId.set(Number(teamId));
  }

  isMyTeam(teamId: number): boolean {
    return this.auth.teamId() === teamId;
  }

  onCsSearchInput(value: string) {
    this.csSearch.set(value);
    if (value.trim().length < 2) {
      this.csResults.set([]);
      this.csShowDropdown.set(false);
    }
    this.searchSubject.next(value);
  }

  pickCsPlayer(player: any) {
    const teamId = this.auth.teamId();
    if (!teamId) return;
    this.csSearch.set(player.fullName);
    this.csResults.set([]);
    this.csShowDropdown.set(false);
    this.csSaving.set(true);

    this.api.setConnSmythe(teamId, player.id).subscribe({
      next: () => {
        this.csSaving.set(false);
        this.snackBar.open(`Conn Smythe pick set to ${player.fullName}!`, 'OK', { duration: 3000 });
        this.loadTeams();
      },
      error: (err) => {
        this.csSaving.set(false);
        this.snackBar.open(err.error?.message || 'Failed to save Conn Smythe pick', 'OK', { duration: 4000 });
      }
    });
  }

  closeCsDropdown() {
    setTimeout(() => this.csShowDropdown.set(false), 150);
  }

  /** For a given series, find ALL teams' predictions, ordered by team list */
  getAllPredsForSeries(seriesId: number): { teamId: number; teamName: string; pred: any }[] {
    return this.allTeams().map(team => ({
      teamId: team.teamId,
      teamName: team.teamName,
      pred: this.allTeamPredictions().find(
        (p: any) => p.series?.id === seriesId && p.team?.id === team.teamId
      ) ?? null,
    }));
  }
}
