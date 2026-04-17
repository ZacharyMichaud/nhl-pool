import { Component, computed, ElementRef, inject, OnDestroy, OnInit, signal } from '@angular/core';
import { CommonModule } from '@angular/common';
import { ApiService } from '../../core/api.service';
import { AuthService } from '../../core/auth.service';
import { DropdownComponent } from '../../shared/components/dropdown/dropdown.component';
import { DropdownOption } from '../../shared/components/dropdown/dropdown.types';

@Component({
  selector: 'app-my-team',
  standalone: true,
  imports: [CommonModule, DropdownComponent],
  templateUrl: './my-team.component.html',
  styleUrl: './my-team.component.scss',
})
export class MyTeamComponent implements OnInit, OnDestroy {
  private api    = inject(ApiService);
  private el     = inject(ElementRef);
  protected auth = inject(AuthService);

  allTeams       = signal<any[]>([]);
  selectedTeamId = signal<number | null>(null);

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

  private outsideClickHandler = (e: MouseEvent) => {
    if (!this.el.nativeElement.contains(e.target)) { /* handled inside component */ }
  };

  ngOnInit() {
    this.api.getStandings().subscribe((standings) => {
      this.allTeams.set(standings);
      const myId = this.auth.teamId();
      const mine = standings.find((s: any) => s.teamId === myId);
      this.selectedTeamId.set(mine?.teamId ?? standings[0]?.teamId ?? null);
    });
  }

  ngOnDestroy() {}

  selectTeam(teamId: number) {
    this.selectedTeamId.set(Number(teamId));
  }

  isMyTeam(teamId: number): boolean {
    return this.auth.teamId() === teamId;
  }
}
