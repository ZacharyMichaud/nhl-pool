import { Component, computed, ElementRef, inject, OnDestroy, OnInit, signal } from '@angular/core';
import { CommonModule } from '@angular/common';
import { FormsModule } from '@angular/forms';
import { ActivatedRoute } from '@angular/router';
import { debounceTime, distinctUntilChanged, Subject, switchMap } from 'rxjs';
import { ApiService } from '../../core/api.service';
import { AuthService } from '../../core/auth.service';
import { DropdownComponent } from '../../shared/components/dropdown/dropdown.component';
import { DropdownOption } from '../../shared/components/dropdown/dropdown.types';
import { MatSnackBar, MatSnackBarModule } from '@angular/material/snack-bar';

/** Primary colors for each NHL team, keyed by 3-letter abbreviation. */
const NHL_TEAM_COLORS: Record<string, { bg: string; light: boolean }> = {
  ANA: { bg: '#F47A38', light: false }, ARI: { bg: '#8C2633', light: true  },
  BOS: { bg: '#FFB81C', light: false }, BUF: { bg: '#003087', light: true  },
  CGY: { bg: '#C8102E', light: true  }, CAR: { bg: '#CC0000', light: true  },
  CHI: { bg: '#CF0A2C', light: true  }, COL: { bg: '#6F263D', light: true  },
  CBJ: { bg: '#002654', light: true  }, DAL: { bg: '#006847', light: true  },
  DET: { bg: '#CE1126', light: true  }, EDM: { bg: '#FF4C00', light: false },
  FLA: { bg: '#041E42', light: true  }, LAK: { bg: '#111111', light: true  },
  MIN: { bg: '#154734', light: true  }, MTL: { bg: '#AF1E2D', light: true  },
  NSH: { bg: '#FFB81C', light: false }, NJD: { bg: '#CE1126', light: true  },
  NYI: { bg: '#00539B', light: true  }, NYR: { bg: '#0038A8', light: true  },
  OTT: { bg: '#C52032', light: true  }, PHI: { bg: '#F74902', light: false },
  PIT: { bg: '#FCB514', light: false }, SEA: { bg: '#001628', light: true  },
  SJS: { bg: '#006D75', light: true  }, STL: { bg: '#002F87', light: true  },
  TBL: { bg: '#002868', light: true  }, TOR: { bg: '#003E7E', light: true  },
  UTA: { bg: '#71AFE5', light: false }, VAN: { bg: '#00843D', light: true  },
  VGK: { bg: '#B4975A', light: false }, WSH: { bg: '#CF0A2C', light: true  },
  WPG: { bg: '#041E42', light: true  },
};

/** Distinct accent colors for pool teams (assigned by index in standings). */
const POOL_TEAM_PALETTE = [
  '#00C3FF', '#FF6B6B', '#FFD166', '#06D6A0', '#A78BFA',
  '#FB923C', '#F472B6', '#34D399', '#60A5FA', '#FBBF24',
  '#E879F9', '#4ADE80',
];

@Component({
  selector: 'app-my-team',
  standalone: true,
  imports: [CommonModule, FormsModule, DropdownComponent, MatSnackBarModule],
  templateUrl: './my-team.component.html',
  styleUrl: './my-team.component.scss',
})
export class MyTeamComponent implements OnInit, OnDestroy {
  private api      = inject(ApiService);
  private el       = inject(ElementRef);
  private snackBar = inject(MatSnackBar);
  private route    = inject(ActivatedRoute);
  protected auth   = inject(AuthService);

  // ── Teams ─────────────────────────────────────────────────────────────────
  allTeams       = signal<any[]>([]);
  selectedTeamId = signal<number | null>(null);

  // ── Conn Smythe search ────────────────────────────────────────────────────
  csSearch       = signal('');
  csResults      = signal<any[]>([]);
  csSaving       = signal(false);
  csShowDropdown = signal(false);

  private searchSubject = new Subject<string>();

  // ── Computed ──────────────────────────────────────────────────────────────
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

  /** Maps teamId → palette color, stable across renders. */
  teamColorMap = computed<Record<number, string>>(() => {
    const map: Record<number, string> = {};
    this.allTeams().forEach((t: any, i: number) => {
      map[t.teamId] = POOL_TEAM_PALETTE[i % POOL_TEAM_PALETTE.length];
    });
    return map;
  });

  getPoolTeamColor(teamId: number): string {
    return this.teamColorMap()[teamId] ?? '#00C3FF';
  }

  // ── Lifecycle ─────────────────────────────────────────────────────────────
  ngOnInit() {
    // Read optional teamId query param (e.g. navigated from Standings)
    const paramTeamId = this.route.snapshot.queryParamMap.get('teamId');
    this.loadTeams(paramTeamId ? Number(paramTeamId) : null);

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

  // ── Data loading ──────────────────────────────────────────────────────────
  loadTeams(preselectedTeamId: number | null = null) {
    this.api.getStandings().subscribe(standings => {
      this.allTeams.set(standings);
      const myId = this.auth.teamId();
      // Priority: query-param team → my team → first team
      const target =
        (preselectedTeamId != null && standings.find((s: any) => s.teamId === preselectedTeamId))
          ? preselectedTeamId
          : standings.find((s: any) => s.teamId === myId)?.teamId
          ?? standings[0]?.teamId
          ?? null;
      this.selectedTeamId.set(target);
      const selected = standings.find((s: any) => s.teamId === target);
      if (selected?.connSmythePlayerName) {
        this.csSearch.set(selected.connSmythePlayerName);
      }
    });
  }

  selectTeam(teamId: number) {
    this.selectedTeamId.set(Number(teamId));
  }

  isMyTeam(teamId: number): boolean {
    return this.auth.teamId() === teamId;
  }

  // ── NHL team color helpers (kept for template use) ────────────────────────
  getTeamColor(abbrev: string): string {
    return NHL_TEAM_COLORS[abbrev?.toUpperCase()]?.bg ?? '#1e2530';
  }

  getTeamTextColor(abbrev: string): string {
    return NHL_TEAM_COLORS[abbrev?.toUpperCase()]?.light ? '#ffffff' : '#000000';
  }

  // ── Conn Smythe ───────────────────────────────────────────────────────────
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
}
