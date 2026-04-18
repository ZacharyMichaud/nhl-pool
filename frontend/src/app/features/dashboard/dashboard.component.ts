import { Component, computed, inject, OnInit, signal } from '@angular/core';
import { CommonModule } from '@angular/common';
import { RouterLink } from '@angular/router';
import { ApiService } from '../../core/api.service';
import { AuthService } from '../../core/auth.service';
import { DropdownComponent } from '../../shared/components/dropdown/dropdown.component';
import { DropdownOption } from '../../shared/components/dropdown/dropdown.types';
import { forkJoin } from 'rxjs';

@Component({
  selector: 'app-dashboard',
  standalone: true,
  imports: [CommonModule, RouterLink, DropdownComponent],
  templateUrl: './dashboard.component.html',
  styleUrl: './dashboard.component.scss',
})
export class DashboardComponent implements OnInit {
  auth = inject(AuthService);
  private api = inject(ApiService);

  standings = signal<any[]>([]);
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
    return [...filtered]
      .sort((a, b) => (b.player?.playoffPoints ?? 0) - (a.player?.playoffPoints ?? 0))
      .slice(0, 10); // Show top 10 for dashboard
  });

  get teamDropdownValue(): string {
    const id = this.selectedTeamId();
    return id === null ? '' : String(id);
  }

  setTeam(value: string) {
    this.selectedTeamId.set(value === '' ? null : Number(value));
  }

  ngOnInit() {
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

  teamColorIndex(teamId: number | undefined | null): number {
    const teams = this.poolTeams();
    const idx = teams.findIndex(t => t.id === teamId);
    return idx === -1 ? 0 : idx % 10;
  }
}
