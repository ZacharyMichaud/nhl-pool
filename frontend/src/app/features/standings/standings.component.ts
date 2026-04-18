import { Component, computed, inject, OnInit, signal } from '@angular/core';
import { CommonModule } from '@angular/common';
import { ApiService } from '../../core/api.service';
import { DropdownComponent } from '../../shared/components/dropdown/dropdown.component';
import { DropdownOption } from '../../shared/components/dropdown/dropdown.types';
import { forkJoin } from 'rxjs';

@Component({
  selector: 'app-standings',
  standalone: true,
  imports: [CommonModule, DropdownComponent],
  templateUrl: './standings.component.html',
  styleUrl: './standings.component.scss',
})
export class StandingsComponent implements OnInit {
  private api = inject(ApiService);

  view = signal<'teams' | 'players'>('teams');
  standings = signal<any[]>([]);
  allPicks = signal<any[]>([]);
  poolTeams = signal<any[]>([]);
  selectedTeamId = signal<number | null>(null);
  expanded: number | null = null;

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
    return [...filtered].sort((a, b) => (b.player?.playoffPoints ?? 0) - (a.player?.playoffPoints ?? 0));
  });

  get teamDropdownValue(): string {
    const id = this.selectedTeamId();
    return id === null ? '' : String(id);
  }

  setTeam(value: string) {
    this.selectedTeamId.set(value === '' ? null : Number(value));
  }

  ngOnInit() {
    this.loadData();
  }

  loadData() {
    forkJoin({
      standings: this.api.getStandings(),
      teams: this.api.getTeams(),
      picks: this.api.getDraftedPlayoffStats()
    }).subscribe({
      next: (data) => {
        this.standings.set(data.standings);
        this.poolTeams.set(data.teams);
        this.allPicks.set(data.picks);
      },
      error: () => {},
    });
  }

  toggle(id: number) {
    this.expanded = this.expanded === id ? null : id;
  }

  formatPosition(pos: string | null | undefined): string {
    if (!pos) return '';
    if (pos === 'L') return 'LW';
    if (pos === 'R') return 'RW';
    return pos;
  }

  teamColorIndex(teamId: number | undefined | null): number {
    const teams = this.poolTeams();
    const idx = teams.findIndex(t => t.id === teamId);
    return idx === -1 ? 0 : idx % 10;
  }
}
