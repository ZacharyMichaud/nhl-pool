import { inject, Injectable, OnDestroy, signal } from '@angular/core';
import { catchError, of, Subscription } from 'rxjs';
import { ApiService } from './api.service';
import { DraftEventService } from './draft-event.service';

@Injectable({ providedIn: 'root' })
export class LiveGameService implements OnDestroy {
  private readonly api         = inject(ApiService);
  private readonly draftEvent  = inject(DraftEventService);
  private statsSub?: Subscription;

  /** The set of NHL team abbreviations (uppercased) currently in a live game. */
  readonly liveTeams = signal<Set<string>>(new Set());

  constructor() {
    this.refresh();
    // Refresh whenever the backend broadcasts a stats-updated event (goal or game end)
    this.statsSub = this.draftEvent.statsUpdated$.subscribe(() => this.refresh());
  }

  /** Returns true if the given NHL team abbreviation is currently playing live. */
  isLive(teamAbbrev: string | null | undefined): boolean {
    if (!teamAbbrev) return false;
    return this.liveTeams().has(teamAbbrev.toUpperCase());
  }

  private refresh(): void {
    this.api.getLiveGames()
      .pipe(catchError(() => of([] as string[])))
      .subscribe(abbrevs => {
        this.liveTeams.set(new Set((abbrevs ?? []).map(a => a.toUpperCase())));
      });
  }

  ngOnDestroy(): void {
    this.statsSub?.unsubscribe();
  }
}
