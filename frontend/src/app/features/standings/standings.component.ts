import { Component, inject, OnInit, signal } from '@angular/core';
import { CommonModule } from '@angular/common';
import { Router } from '@angular/router';
import { ApiService } from '../../core/api.service';
import { AuthService } from '../../core/auth.service';

@Component({
  selector: 'app-standings',
  standalone: true,
  imports: [CommonModule],
  templateUrl: './standings.component.html',
  styleUrl: './standings.component.scss',
})
export class StandingsComponent implements OnInit {
  private api    = inject(ApiService);
  private router = inject(Router);
  protected auth = inject(AuthService);

  standings = signal<any[]>([]);

  ngOnInit() {
    this.api.getStandings().subscribe({
      next: (data) => this.standings.set(data),
      error: () => {},
    });
  }

  isMyTeam(teamId: number): boolean {
    return this.auth.teamId() === teamId;
  }

  goToTeam(teamId: number) {
    this.router.navigate(['/teams'], { queryParams: { teamId } });
  }
}
