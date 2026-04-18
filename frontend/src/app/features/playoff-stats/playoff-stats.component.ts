import { CommonModule } from '@angular/common';
import { Component, computed, inject, OnInit, signal } from '@angular/core';
import { catchError, EMPTY } from 'rxjs';
import { ApiService } from '../../core/api.service';
import { DropdownComponent } from '../../shared/components/dropdown/dropdown.component';
import { DropdownOption } from '../../shared/components/dropdown/dropdown.types';

@Component({
  selector: 'app-playoff-stats',
  standalone: true,
  imports: [CommonModule, DropdownComponent],
  templateUrl: './playoff-stats.component.html',
  styleUrl: './playoff-stats.component.scss'
})
export class PlayoffStatsComponent implements OnInit {
  private api = inject(ApiService);

  allPicks = signal<any[]>([]);
  poolTeams = signal<any[]>([]);
  selectedTeamId = signal<number | null>(null);

  teamOptions = computed<DropdownOption[]>(() => {
    const opts: DropdownOption[] = [{ value: '', label: 'All Teams' }];
    for (const t of this.poolTeams()) {
      opts.push({ value: String(t.id), label: t.name });
    }
    return opts;
  });

  filteredPicks = computed(() => {
    const id = this.selectedTeamId();
    const picks = this.allPicks();
    const filtered = id === null ? picks : picks.filter((p: any) => p.team?.id === id);
    return [...filtered].sort(
      (a, b) => (b.player?.playoffPoints ?? 0) - (a.player?.playoffPoints ?? 0)
    );
  });

  /** Summary stats per pool team, sorted by total playoff PTS desc */
  teamSummaries = computed(() => {
    const teams = this.poolTeams();
    const picks = this.allPicks();
    return teams
      .map(team => {
        const roster = picks.filter((p: any) => p.team?.id === team.id);
        return {
          id: team.id,
          name: team.name,
          totalPts: roster.reduce((s: number, p: any) => s + (p.player?.playoffPoints ?? 0), 0),
          totalGoals: roster.reduce((s: number, p: any) => s + (p.player?.playoffGoals ?? 0), 0),
          totalAssists: roster.reduce((s: number, p: any) => s + (p.player?.playoffAssists ?? 0), 0),
          activePlayers: roster.filter((p: any) => !p.player?.eliminated).length,
          playerCount: roster.length
        };
      })
      .sort((a, b) => b.totalPts - a.totalPts);
  });

  get teamDropdownValue(): string {
    const id = this.selectedTeamId();
    return id === null ? '' : String(id);
  }

  setTeam(value: string) {
    this.selectedTeamId.set(value === '' ? null : Number(value));
  }

  formatPosition(pos: string | null | undefined): string {
    if (!pos) {
      return '';
    }
    if (pos === 'L') {
      return 'LW';
    }
    if (pos === 'R') {
      return 'RW';
    }
    return pos;
  }

  teamColorIndex(teamId: number | undefined | null): number {
    const teams = this.poolTeams();
    const idx = teams.findIndex(t => t.id === teamId);
    return idx === -1 ? 0 : idx % 10;
  }

  ngOnInit() {
    this.api.getTeams()
      .pipe(catchError(() => EMPTY))
      .subscribe(teams => {
        this.poolTeams.set(teams);
        this.loadStats();
      });
  }

  private loadStats() {
    this.api.getDraftedPlayoffStats()
      .pipe(catchError(() => EMPTY))
      .subscribe(picks => this.allPicks.set(picks));
  }
}
