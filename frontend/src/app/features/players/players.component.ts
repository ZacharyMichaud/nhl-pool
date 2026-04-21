import { Component, computed, inject, OnDestroy, OnInit, signal } from '@angular/core';
import { CommonModule, DecimalPipe } from '@angular/common';
import { ApiService } from '../../core/api.service';
import { DraftEventService } from '../../core/draft-event.service';
import { LiveGameService } from '../../core/live-game.service';
import { DropdownComponent } from '../../shared/components/dropdown/dropdown.component';
import { DropdownOption } from '../../shared/components/dropdown/dropdown.types';
import { PoolBadgeComponent } from '../../shared/components/pool-badge/pool-badge.component';
import { forkJoin, Subscription } from 'rxjs';

type SortCol = 'pts' | 'g' | 'a' | 'gp' | 'ppg' | 'ppp' | 'toi';

@Component({
  selector: 'app-players',
  standalone: true,
  imports: [CommonModule, DecimalPipe, DropdownComponent, PoolBadgeComponent],
  templateUrl: './players.component.html',
  styleUrl: './players.component.scss',
})
export class PlayersComponent implements OnInit, OnDestroy {
  private api = inject(ApiService);
  private draftEvent = inject(DraftEventService);
  protected liveGame = inject(LiveGameService);
  private statsSub?: Subscription;

  allPicks       = signal<any[]>([]);
  poolTeams      = signal<any[]>([]);
  selectedTeamId = signal<number | null>(null);

  // ── Sorting ──
  sortCol: SortCol = 'pts';
  sortDir: 'asc' | 'desc' = 'desc';

  // ── Player detail modal ──
  selectedPick = signal<any>(null);

  teamOptions = computed<DropdownOption[]>(() => {
    const opts: DropdownOption[] = [{ value: '', label: 'All Teams' }];
    for (const t of this.poolTeams()) {
      opts.push({ value: String(t.id), label: t.name });
    }
    return opts;
  });

  filteredPicks = computed(() => {
    const id    = this.selectedTeamId();
    const picks = this.allPicks();
    const filtered = id === null ? picks : picks.filter((p: any) => p.team?.id === id);
    return this.sort(filtered);
  });

  get teamDropdownValue(): string {
    const id = this.selectedTeamId();
    return id === null ? '' : String(id);
  }

  setTeam(value: string) {
    this.selectedTeamId.set(value === '' ? null : Number(value));
  }

  sortBy(col: SortCol) {
    if (this.sortCol === col) {
      this.sortDir = this.sortDir === 'desc' ? 'asc' : 'desc';
    } else {
      this.sortCol = col;
      this.sortDir = 'desc';
    }
  }

  getSortArrow(col: SortCol): string {
    if (this.sortCol !== col) return '';
    return this.sortDir === 'asc' ? '▲' : '▼';
  }

  private sort(picks: any[]): any[] {
    const dir = this.sortDir === 'desc' ? -1 : 1;
    return [...picks].sort((a, b) => (this.rawStat(a) - this.rawStat(b)) * dir);
  }

  private rawStat(pick: any): number {
    const p = pick.player;
    if (!p) return 0;
    switch (this.sortCol) {
      case 'gp':  return p.playoffGamesPlayed ?? 0;
      case 'g':   return p.playoffGoals ?? 0;
      case 'a':   return p.playoffAssists ?? 0;
      case 'pts': return p.playoffPoints ?? 0;
      case 'ppg': return p.playoffPowerPlayGoals ?? 0;
      case 'ppp': return p.playoffPowerPlayPoints ?? 0;
      case 'toi': return this.toiToMinutes(p.playoffAvgToi);
      default:    return 0;
    }
  }

  private toiToMinutes(toi: string | null): number {
    if (!toi) return 0;
    const parts = toi.split(':');
    return parseInt(parts[0], 10) + parseInt(parts[1] || '0', 10) / 60;
  }

  teamColorIndex(teamId: number | undefined | null): number {
    const teams = this.poolTeams();
    const idx = teams.findIndex(t => t.id === teamId);
    return idx === -1 ? 0 : idx % 10;
  }

  formatPosition(pos: string | null | undefined): string {
    if (!pos) return '';
    if (pos === 'L') return 'LW';
    if (pos === 'R') return 'RW';
    return pos;
  }

  // ── Modal ──

  openModal(pick: any) {
    if (window.innerWidth >= 600) return; // desktop: no modal
    this.selectedPick.set(pick);
  }

  closeModal() {
    this.selectedPick.set(null);
  }

  ngOnInit() {
    this.draftEvent.connect();
    forkJoin({
      picks: this.api.getDraftedPlayoffStats(),
      teams: this.api.getTeams(),
    }).subscribe({
      next: ({ picks, teams }) => {
        this.allPicks.set(picks);
        this.poolTeams.set(teams);
      },
      error: () => {},
    });

    // Reload player stats whenever the backend broadcasts a sync
    this.statsSub = this.draftEvent.statsUpdated$.subscribe(() => {
      this.api.getDraftedPlayoffStats().subscribe(picks => this.allPicks.set(picks));
    });
  }

  ngOnDestroy() {
    this.statsSub?.unsubscribe();
  }
}
