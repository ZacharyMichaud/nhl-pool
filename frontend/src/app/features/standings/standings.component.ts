import { Component, inject, OnInit, signal } from '@angular/core';
import { CommonModule } from '@angular/common';
import { ApiService } from '../../core/api.service';

@Component({
  selector: 'app-standings',
  standalone: true,
  imports: [CommonModule],
  templateUrl: './standings.component.html',
  styleUrl: './standings.component.scss',
})
export class StandingsComponent implements OnInit {
  private api = inject(ApiService);

  standings = signal<any[]>([]);
  expanded: number | null = null;

  ngOnInit() {
    this.api.getStandings().subscribe((s) => this.standings.set(s));
  }

  toggle(id: number) {
    this.expanded = this.expanded === id ? null : id;
  }
}
