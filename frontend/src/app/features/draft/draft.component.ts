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
import { DropdownComponent } from '../../shared/components/dropdown/dropdown.component';
import { DropdownOption } from '../../shared/components/dropdown/dropdown.types';

type SortColumn = 'gp' | 'g' | 'a' | 'pts' | 'ppg' | 'pwg' | 'ppp' | 'toi';
type SplitType = 'full' | 'last41' | 'last20';
type MobileTab = 'players' | 'watchlist' | 'history';

@Component({
  selector: 'app-draft',
  standalone: true,
  imports: [CommonModule, FormsModule, MatFormFieldModule, MatInputModule, MatSelectModule, MatButtonModule, MatSnackBarModule, DropdownComponent],
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

  // ── Draft History ──
  /** null = show all teams */
  selectedHistoryTeamId = signal<number | null>(null);

  poolTeamOptions = computed<DropdownOption[]>(() => {
    const seen = new Map<number, string>();
    for (const pick of this.board()) {
      if (pick.team) seen.set(pick.team.id, pick.team.name);
    }
    const opts: DropdownOption[] = [{ value: '', label: 'All Teams' }];
    seen.forEach((name, id) => opts.push({ value: String(id), label: name }));
    return opts;
  });

  myHistoryTeamOption = computed<DropdownOption[]>(() => {
    const myId = this.auth.teamId();
    if (!myId) return this.poolTeamOptions();
    const all = this.poolTeamOptions();
    return all;
  });

  filteredHistory = computed(() => {
    const id = this.selectedHistoryTeamId();
    if (id === null) return this.board();
    return this.board().filter(p => p.team?.id === id);
  });

  // ── Watchlist ──
  watchlist = signal<any[]>([]);
  watchlistIds = computed<Set<number>>(() => new Set(this.watchlist().map((e: any) => e.player.id)));

  // ── Mobile tab navigation ──
  activeTab = signal<MobileTab>('players');

  searchQuery = '';
  selectedTeam = '';
  selectedPosition = '';
  sortColumn: SortColumn = 'pts';
  sortDirection: 'asc' | 'desc' = 'desc';
  selectedSplit: SplitType = 'full';
  perGameMode = false;

  teams = signal<string[]>([]);

  splitOptions = [
    { value: 'full' as SplitType, label: 'Full Season' },
    { value: 'last41' as SplitType, label: 'Last 41' },
    { value: 'last20' as SplitType, label: 'Last 20' },
  ];

  positionOptions: DropdownOption[] = [
    { value: '', label: 'All Positions' },
    { value: 'C', label: 'Centre' },
    { value: 'L', label: 'Left Wing' },
    { value: 'R', label: 'Right Wing' },
    { value: 'D', label: 'Defence' },
  ];

  teamOptions = computed<DropdownOption[]>(() => [
    { value: '', label: 'All Teams' },
    ...this.teams().map(t => ({ value: t, label: t })),
  ]);

  mobileTabs: { id: MobileTab; label: string; icon: string }[] = [
    { id: 'players', label: 'Players', icon: '🏒' },
    { id: 'watchlist', label: 'Watchlist', icon: '⭐' },
    { id: 'history', label: 'History', icon: '📋' },
  ];

  private pollInterval: any;

  ngOnInit() {
    document.body.classList.add('draft-open'); // lock outer scroll on mobile for this page
    this.loadPlayers();
    this.loadBoard();
    this.loadWatchlist();
    this.api.getDraftConfig()
      .pipe(catchError(() => EMPTY))
      .subscribe((c) => this.config.set(c));

    this.pollInterval = setInterval(() => {
      this.loadBoard();
      this.loadWatchlist();
      this.api.getDraftConfig()
        .pipe(catchError(() => EMPTY))
        .subscribe((c) => this.config.set(c));
    }, 5000);
  }

  ngOnDestroy() {
    document.body.classList.remove('draft-open'); // restore scroll for other pages
    if (this.pollInterval) clearInterval(this.pollInterval);
  }

  // ── Players ──

  loadPlayers() {
    this.api.getAvailablePlayers(this.selectedTeam || undefined, this.selectedPosition || undefined)
      .subscribe((p) => {
        this.players.set(p);
        if (this.teams().length === 0) {
          this.teams.set([...new Set(p.map((pl: any) => pl.teamAbbrev as string))].sort());
        }
        this.filterPlayers();
      });
  }

  filterPlayers() {
    const q = this.searchQuery.toLowerCase();
    let result = q
      ? this.players().filter((p) => `${p.firstName} ${p.lastName}`.toLowerCase().includes(q))
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
    if (this.sortColumn !== col) return '';
    return this.sortDirection === 'asc' ? '▲' : '▼';
  }

  // ── Stat Accessors ──

  getStat(p: any, col: SortColumn): string {
    const val = this.getRawStat(p, col);
    if (col === 'gp') return '' + val;
    if (col === 'ppg') return this.formatDecimal(val);
    if (col === 'toi') {
      const s = this.selectedSplit;
      if (s === 'last41') return p.last41AvgToi || '-';
      if (s === 'last20') return p.last20AvgToi || '-';
      return p.regularSeasonAvgToi || '-';
    }
    return '' + val;
  }

  getDisplayStat(p: any, col: SortColumn): string {
    const raw = this.getRawStat(p, col);
    if (col === 'ppg') return this.formatDecimal(raw);
    if (!this.perGameMode) return '' + raw;
    const gp = this.getGamesPlayed(p);
    if (gp === 0) return '-';
    return this.formatDecimal(raw / gp);
  }

  private formatDecimal(val: number): string {
    return val === 0 ? '0.00' : val.toFixed(2);
  }

  private getGamesPlayed(p: any): number {
    switch (this.selectedSplit) {
      case 'last41': return p.last41GamesPlayed || 0;
      case 'last20': return p.last20GamesPlayed || 0;
      default: return p.regularSeasonGamesPlayed || 0;
    }
  }

  private getRawStat(p: any, col: SortColumn): number {
    const s = this.selectedSplit;
    switch (col) {
      case 'gp': return this.getGamesPlayed(p);
      case 'g': return s === 'last41' ? (p.last41Goals || 0) : s === 'last20' ? (p.last20Goals || 0) : (p.regularSeasonGoals || 0);
      case 'a': return s === 'last41' ? (p.last41Assists || 0) : s === 'last20' ? (p.last20Assists || 0) : (p.regularSeasonAssists || 0);
      case 'pts': return s === 'last41' ? (p.last41Points || 0) : s === 'last20' ? (p.last20Points || 0) : (p.regularSeasonPoints || 0);
      case 'ppg': {
        const gp = this.getGamesPlayed(p);
        const pts = this.getRawStat(p, 'pts');
        return gp > 0 ? pts / gp : 0;
      }
      case 'pwg': return s === 'last41' ? (p.last41PowerPlayGoals || 0) : s === 'last20' ? (p.last20PowerPlayGoals || 0) : (p.regularSeasonPowerPlayGoals || 0);
      case 'ppp': return s === 'last41' ? (p.last41PowerPlayPoints || 0) : s === 'last20' ? (p.last20PowerPlayPoints || 0) : (p.regularSeasonPowerPlayPoints || 0);
      case 'toi': {
        const s = this.selectedSplit;
        if (s === 'last41') return this.toiToMinutes(p.last41AvgToi);
        if (s === 'last20') return this.toiToMinutes(p.last20AvgToi);
        return this.toiToMinutes(p.regularSeasonAvgToi);
      }
      default: return 0;
    }
  }

  private applySorting(players: any[]): any[] {
    const dir = this.sortDirection === 'desc' ? -1 : 1;
    return players.sort((a, b) => {
      let valA = this.getRawStat(a, this.sortColumn);
      let valB = this.getRawStat(b, this.sortColumn);
      if (this.perGameMode && !['gp', 'ppg', 'toi'].includes(this.sortColumn)) {
        valA = valA / (this.getGamesPlayed(a) || 1);
        valB = valB / (this.getGamesPlayed(b) || 1);
      }
      return (valA - valB) * dir;
    });
  }

  private toiToMinutes(toi: string | null): number {
    if (!toi) return 0;
    const parts = toi.split(':');
    return parseInt(parts[0], 10) + parseInt(parts[1] || '0', 10) / 60;
  }

  // ── Draft Actions ──

  loadBoard() {
    this.api.getDraftBoard()
      .pipe(catchError(() => EMPTY))
      .subscribe((b) => {
        this.board.set(b);
        this.initHistoryFilter();
      });
  }

  isMyTurn(): boolean {
    const cfg = this.config();
    if (!cfg || cfg.status !== 'IN_PROGRESS') return false;
    return this.auth.teamId() != null;
  }

  draftPlayer(player: any) {
    if (this.config()?.status !== 'IN_PROGRESS') return;
    this.api.makePick(player.id).subscribe({
      next: () => {
        this.snackBar.open(`✅ Drafted ${player.firstName} ${player.lastName}!`, 'OK', { duration: 3000 });
        this.loadPlayers();
        this.loadBoard();
        this.loadWatchlist();
        this.api.getDraftConfig().subscribe((c) => this.config.set(c));
      },
      error: (err) => this.snackBar.open(err.error?.error || 'Pick failed', 'OK', { duration: 4000 }),
    });
  }

  // ── Watchlist ──

  loadWatchlist() {
    if (!this.auth.teamId()) return;
    this.api.getWatchlist()
      .pipe(catchError(() => EMPTY))
      .subscribe((entries) => this.watchlist.set(entries));
  }

  isWatchlisted(player: any): boolean {
    return this.watchlistIds().has(player.id);
  }

  toggleWatchlist(player: any) {
    if (!this.auth.teamId()) {
      this.snackBar.open('You must be on a team to use the watchlist', 'OK', { duration: 3000 });
      return;
    }
    if (this.isWatchlisted(player)) {
      this.api.removeFromWatchlist(player.id).subscribe(() => this.loadWatchlist());
    } else {
      this.api.addToWatchlist(player.id).subscribe(() => {
        this.loadWatchlist();
        this.snackBar.open(`⭐ Added ${player.firstName} ${player.lastName} to watchlist`, 'OK', { duration: 2000 });
      });
    }
  }

  moveUp(index: number) {
    if (index === 0) return;
    const list = [...this.watchlist()];
    [list[index - 1], list[index]] = [list[index], list[index - 1]];
    this.watchlist.set(list);
    this.persistReorder(list);
  }

  moveDown(index: number) {
    const list = [...this.watchlist()];
    if (index === list.length - 1) return;
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

  // ── Mobile helpers ──

  setTab(tab: MobileTab) {
    this.activeTab.set(tab);
  }

  watchlistCount = computed(() => this.watchlist().length);

  // ── Player detail modal ──

  selectedPlayer = signal<any>(null);

  openPlayerModal(p: any) {
    this.selectedPlayer.set(p);
  }

  closePlayerModal() {
    this.selectedPlayer.set(null);
  }

  getModalStats(p: any): { key: string; label: string; val: string }[] {
    return [
      { key: 'gp',  label: 'GP',     val: this.getStat(p, 'gp') },
      { key: 'g',   label: 'G',      val: this.getDisplayStat(p, 'g') },
      { key: 'a',   label: 'A',      val: this.getDisplayStat(p, 'a') },
      { key: 'pts', label: 'PTS',    val: this.getDisplayStat(p, 'pts') },
      { key: 'ppg', label: 'P/GP',   val: this.getDisplayStat(p, 'ppg') },
      { key: 'pwg', label: 'PPG',    val: this.getDisplayStat(p, 'pwg') },
      { key: 'ppp', label: 'PPP',    val: this.getDisplayStat(p, 'ppp') },
      ...(this.selectedSplit === 'full'
        ? [{ key: 'toi', label: 'TOI/GP', val: p.regularSeasonAvgToi || '-' }]
        : []),
    ];
  }

  // ── History helpers ──

  setHistoryTeam(value: string) {
    this.selectedHistoryTeamId.set(value === '' ? null : Number(value));
  }

  get historyTeamValue(): string {
    const id = this.selectedHistoryTeamId();
    return id === null ? '' : String(id);
  }

  /** Initialise history filter to user's own team once board loads */
  private initHistoryFilter() {
    const myId = this.auth.teamId();
    if (myId != null && this.selectedHistoryTeamId() === null) {
      // Default to user's own team if they have one
      const hasPick = this.board().some(p => p.team?.id === myId);
      if (hasPick) this.selectedHistoryTeamId.set(myId as number);
    }
  }
}
