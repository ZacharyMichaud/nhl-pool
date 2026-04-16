import { Component, inject, OnInit, signal } from '@angular/core';
import { CommonModule } from '@angular/common';
import { ApiService } from '../../core/api.service';
import { AuthService } from '../../core/auth.service';

@Component({
  selector: 'app-my-team',
  standalone: true,
  imports: [CommonModule],
  templateUrl: './my-team.component.html',
  styleUrl: './my-team.component.scss',
})
export class MyTeamComponent implements OnInit {
  private api = inject(ApiService);
  private auth = inject(AuthService);

  myTeam = signal<any>(null);

  ngOnInit() {
    this.api.getStandings().subscribe((standings) => {
      const teamId = this.auth.teamId();
      const mine = standings.find((s: any) => s.teamId === teamId);
      this.myTeam.set(mine || null);
    });
  }
}
