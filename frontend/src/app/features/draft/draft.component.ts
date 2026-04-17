import { CommonModule } from '@angular/common';
import { Component, computed, inject, OnDestroy, OnInit, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { MatButtonModule } from '@angular/material/button';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatInputModule } from '@angular/material/input';
import { MatSelectModule } from '@angular/material/select';
import { MatSnackBar, MatSnackBarModule } from '@angular/material/snack-bar';
import { catchError, EMPTY } from 'rxjs';
import { ApiService } from '../../core/api.service';
import { AuthService } from '../../core/auth.service';

type SortColumn = 'gp' | 'g' | 'a' | 'pts' | 'ppg' | 'pwg' | 'ppp' | 'toi';
type SplitType = 'full' | 'last41' | 'last20';

@Component({
  selector: 'app-draft',
  standalone: true,
  imports: [CommonModule, FormsModule, MatFormFieldModule, MatInputModule, MatSelectModule, MatButtonModule,
    MatSnackBarModule
  ],
  templateUrl: './draft.component.html',
  styleUrl: './draft.component.scss'
})
export class DraftComponent implements OnInit, OnDestroy {
  private api = inject(ApiService);
  protected auth = inject(AuthService);
  private snackBar = inject(MatSnackBar);

  players = signal<any[]>([]);
  filteredPlayers = signal<any[]>([]);
  board = signal<any[]>([]);
  config = signal<any>(null);

  // ── Watchlist ──
  watchlist = signal<any[]>([]); // [{rank, player}]
  watchlistIds = computed<Set<number>>(() => new Set(this.watchlist().map((e: any) => e.player.id)));

  searchQuery = '';
  selectedTeam = '';
  selectedPosition = '';
  sortColumn: SortColumn = 'pts';
  sortDirection: 'asc' | 'desc' = 'desc';
  selectedSplit: SplitType = 'full';
  perGameMode = false;

  teams: string[] = [];

  splitOptions = [
    { value: 'full' as SplitType, label: 'Full Season' },
    { value: 'last41' as SplitType, label: 'Last 41' },
    { value: 'last20' as SplitType, label: 'Last 20' }
  ];

  private pollInterval: any;

  ngOnInit() {
    this.loadPlayers();
    this.loadBoard();
    this.loadWatchlist();
    this.api.getDraftConfig().subscribe((c) => this.config.set(c));

    // Poll every 5 seconds to stay in sync across teammates
    this.pollInterval = setInterval(() => {
      this.loadBoard();
      this.loadWatchlist();
      this.api.getDraftConfig()
        .pipe(catchError(() => EMPTY))
        .subscribe((c) => this.config.set(c));
    }, 5000);
  }

  ngOnDestroy() {
    if (this.pollInterval) {
      clearInterval(this.pollInterval);
    }
  }

  loadPlayers() {
    this.api.getAvailablePlayers(this.selectedTeam || undefined, this.selectedPosition || undefined)
      .subscribe((p) => {
        this.players.set(p);
        if (this.teams.length === 0) {
          this.teams = [...new Set(p.map((pl: any) => pl.teamAbbrev as string))].sort();
        }
        this.filterPlayers();
      });
  }

  filterPlayers() {
    const q = this.searchQuery.toLowerCase();
    let result = q
      ? this.players().filter((p) => `${ p.firstName } ${ p.lastName }`.toLowerCase().includes(q))
      : [...this.players()];
    result = this.applySorting(result);
    this.filteredPlayers.set(result);
  }

  sortBy(column: SortColumn) {
    if (this.sortColumn === column) {
      this.sortDirection = this.sortDirection === 'desc' ? 'asc' : 'desc';
    } else {
      this.sortColumn = column;
      this.sortDirection = 'desc';
    }
    this.filterPlayers();
  }

  getSortArrow(col: SortColumn): string {
    if (this.sortColumn !== col) {
      return '';
    }
    return this.sortDirection === 'asc' ? '▲' : '▼';
  }

  // ── Stat Accessors ──

  /** Get raw stat value for a player based on current split */
  getStat(p: any, col: SortColumn): string {
    const val = this.getRawStat(p, col);
    if (col === 'gp') {
      return '' + val;
    }
    if (col === 'ppg') {
      return this.formatDecimal(val);
    }
    if (col === 'toi') {
      return p.regularSeasonAvgToi || '-';
    }
    return '' + val;
  }

  /** Get display stat — respects per-game mode */
  getDisplayStat(p: any, col: SortColumn): string {
    const raw = this.getRawStat(p, col);
    if (col === 'ppg') {
      return this.formatDecimal(raw);
    }  // P/GP is always per-game
    if (!this.perGameMode) {
      return '' + raw;
    }
    const gp = this.getGamesPlayed(p);
    if (gp === 0) {
      return '-';
    }
    return this.formatDecimal(raw / gp);
  }

  private formatDecimal(val: number): string {
    if (val === 0) {
      return '0.00';
    }
    return val.toFixed(2);
  }

  private getGamesPlayed(p: any): number {
    switch (this.selectedSplit) {
      case 'last41':
        return p.last41GamesPlayed || 0;
      case 'last20':
        return p.last20GamesPlayed || 0;
      default:
        return p.regularSeasonGamesPlayed || 0;
    }
  }

  private getRawStat(p: any, col: SortColumn): number {
    const s = this.selectedSplit;
    switch (col) {
      case 'gp':
        return this.getGamesPlayed(p);
      case 'g':
        return s === 'last41' ? (p.last41Goals || 0) : s === 'last20' ? (p.last20Goals || 0) : (p.regularSeasonGoals
          || 0);
      case 'a':
        return s === 'last41' ? (p.last41Assists || 0) : s === 'last20'
          ? (p.last20Assists || 0)
          : (p.regularSeasonAssists || 0);
      case 'pts':
        return s === 'last41' ? (p.last41Points || 0) : s === 'last20' ? (p.last20Points || 0) : (p.regularSeasonPoints
          || 0);
      case 'ppg': {
        const gp = this.getGamesPlayed(p);
        const pts = this.getRawStat(p, 'pts');
        return gp > 0 ? pts / gp : 0;
      }
      case 'pwg':
        return s === 'last41' ? (p.last41PowerPlayGoals || 0) : s === 'last20'
          ? (p.last20PowerPlayGoals || 0)
          : (p.regularSeasonPowerPlayGoals || 0);
      case 'ppp':
        return s === 'last41' ? (p.last41PowerPlayPoints || 0) : s === 'last20'
          ? (p.last20PowerPlayPoints || 0)
          : (p.regularSeasonPowerPlayPoints || 0);
      case 'toi':
        return this.toiToMinutes(p.regularSeasonAvgToi);
      default:
        return 0;
    }
  }

  // ── Sorting ──

  private applySorting(players: any[]): any[] {
    const dir = this.sortDirection === 'desc' ? -1 : 1;
    return players.sort((a, b) => {
      let valA = this.getRawStat(a, this.sortColumn);
      let valB = this.getRawStat(b, this.sortColumn);
      if (this.perGameMode && !['gp', 'ppg', 'toi'].includes(this.sortColumn)) {
        const gpA = this.getGamesPlayed(a) || 1;
        const gpB = this.getGamesPlayed(b) || 1;
        valA = valA / gpA;
        valB = valB / gpB;
      }
      return (valA - valB) * dir;
    });
  }

  private toiToMinutes(toi: string | null): number {
    if (!toi) {
      return 0;
    }
    const parts = toi.split(':');
    return parseInt(parts[0], 10) + parseInt(parts[1] || '0', 10) / 60;
  }

  // ── Draft Actions ──

  loadBoard() {
    this.api.getDraftBoard().subscribe((b) => this.board.set(b));
  }

  isMyTurn(): boolean {
    const cfg = this.config();
    if (!cfg || cfg.status !== 'IN_PROGRESS') {
      return false;
    }
    return this.auth.teamId() != null;
  }

  draftPlayer(player: any) {
    if (this.config()?.status !== 'IN_PROGRESS') {
      return;
    }
    this.api.makePick(player.id).subscribe({
      next: () => {
        this.snackBar.open(`Drafted ${ player.firstName } ${ player.lastName }!`, 'OK', { duration: 3000 });
        this.loadPlayers();
        this.loadBoard();
        this.api.getDraftConfig().subscribe((c) => this.config.set(c));
      },
      error: (err) => this.snackBar.open(err.error?.error || 'Pick failed', 'OK', { duration: 4000 })
    });
  }

  // ── Watchlist ──

  loadWatchlist() {
    if (!this.auth.teamId()) {
      return;
    }
    this.api.getWatchlist()
      .pipe(catchError(() => EMPTY))
      .subscribe((entries) => this.watchlist.set(entries));
  }

  isWatchlisted(player: any): boolean {
    return this.watchlistIds().has(player.id);
  }

  toggleWatchlist(player: any) {
    if (!this.auth.teamId()) {
      this.snackBar.open('You must be assigned to a team to use the watchlist', 'OK', { duration: 3000 });
      return;
    }
    if (this.isWatchlisted(player)) {
      this.api.removeFromWatchlist(player.id).subscribe(() => this.loadWatchlist());
    } else {
      this.api.addToWatchlist(player.id).subscribe(() => this.loadWatchlist());
    }
  }

  moveUp(index: number) {
    if (index === 0) {
      return;
    }
    const list = [...this.watchlist()];
    [list[index - 1], list[index]] = [list[index], list[index - 1]];
    this.watchlist.set(list);
    this.persistReorder(list);
  }

  moveDown(index: number) {
    const list = [...this.watchlist()];
    if (index === list.length - 1) {
      return;
    }
    [list[index], list[index + 1]] = [list[index + 1], list[index]];
    this.watchlist.set(list);
    this.persistReorder(list);
  }

  removeFromWatchlist(player: any) {
    this.api.removeFromWatchlist(player.id).subscribe(() => this.loadWatchlist());
  }

  private persistReorder(list: any[]) {
    const playerIds = list.map((e: any) => e.player.id);
    this.api.reorderWatchlist(playerIds).subscribe();
  }
}
