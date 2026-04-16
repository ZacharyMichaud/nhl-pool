import { Component, inject, OnInit, signal } from '@angular/core';
import { CommonModule } from '@angular/common';
import { FormsModule } from '@angular/forms';
import { MatButtonModule } from '@angular/material/button';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatInputModule } from '@angular/material/input';
import { MatSelectModule } from '@angular/material/select';
import { MatSnackBar, MatSnackBarModule } from '@angular/material/snack-bar';
import { ApiService } from '../../core/api.service';

@Component({
  selector: 'app-admin',
  standalone: true,
  imports: [CommonModule, FormsModule, MatButtonModule, MatFormFieldModule, MatInputModule, MatSelectModule, MatSnackBarModule],
  templateUrl: './admin.component.html',
  styleUrl: './admin.component.scss',
})
export class AdminComponent implements OnInit {
  private api = inject(ApiService);
  private snackBar = inject(MatSnackBar);

  draftConfig = signal<any>(null);
  teams = signal<any[]>([]);
  users = signal<any[]>([]);
  rounds = signal<any[]>([]);
  scoringRules = signal<any[]>([]);
  predictionRules = signal<any[]>([]);

  playersPerTeam = 10;
  draftOrder = '';
  newTeamName = '';
  assignTeamId = 0;
  assignUserIds = '';

  ngOnInit() {
    this.loadAll();
  }

  loadAll() {
    this.api.getDraftConfig().subscribe((c) => {
      this.draftConfig.set(c);
      this.playersPerTeam = c.playersPerTeam;
      this.draftOrder = c.draftOrder || '';
    });
    this.api.getTeams().subscribe((t) => this.teams.set(t));
    this.api.getUsers().subscribe((u) => this.users.set(u));
    this.api.getRounds().subscribe((r) => this.rounds.set(r));
    this.api.getScoringRules().subscribe((r: any) => {
      this.scoringRules.set(r.playerRules || []);
      this.predictionRules.set(r.predictionRules || []);
    });
  }

  show(msg: string) {
    this.snackBar.open(msg, 'OK', { duration: 3000 });
  }

  syncRosters() {
    this.api.syncRosters().subscribe({
      next: () => this.show('Rosters synced!'),
      error: (e) => this.show(e.error?.error || 'Failed'),
    });
  }

  syncStats() {
    this.api.syncStats().subscribe({
      next: () => this.show('Stats synced!'),
      error: (e) => this.show(e.error?.error || 'Failed'),
    });
  }

  syncAllStats() {
    this.api.syncAllStats().subscribe({
      next: () => this.show('All stats synced!'),
      error: (e) => this.show(e.error?.error || 'Failed'),
    });
  }

  syncSeries() {
    this.api.syncSeries().subscribe({
      next: () => this.show('Series synced!'),
      error: (e) => this.show(e.error?.error || 'Failed'),
    });
  }

  saveDraftConfig() {
    this.api.updateDraftConfig({ playersPerTeam: this.playersPerTeam, draftOrder: this.draftOrder }).subscribe({
      next: () => this.show('Config saved'),
      error: (e) => this.show(e.error?.error || 'Failed'),
    });
  }

  startDraft() {
    this.api.startDraft().subscribe({
      next: () => { this.show('Draft started!'); this.loadAll(); },
      error: (e) => this.show(e.error?.error || 'Failed'),
    });
  }

  resetDraft() {
    this.api.resetDraft().subscribe({
      next: () => { this.show('Draft reset!'); this.loadAll(); },
      error: (e) => this.show(e.error?.error || 'Failed'),
    });
  }

  createTeam() {
    if (!this.newTeamName) return;
    this.api.createTeam(this.newTeamName).subscribe({
      next: () => { this.show('Team created!'); this.newTeamName = ''; this.loadAll(); },
      error: (e) => this.show(e.error?.error || 'Failed'),
    });
  }

  assignMembers() {
    const ids = this.assignUserIds.split(',').map((id) => parseInt(id.trim())).filter((id) => !isNaN(id));
    this.api.assignMembers(this.assignTeamId, ids).subscribe({
      next: () => { this.show('Members assigned!'); this.loadAll(); },
      error: (e) => this.show(e.error?.error || 'Failed'),
    });
  }

  updateRound(r: any) {
    this.api.updateRoundStatus(r.id, r.status).subscribe({
      next: () => this.show('Round updated'),
      error: (e) => this.show(e.error?.error || 'Failed'),
    });
  }

  updateScoringRule(rule: any) {
    this.api.updateScoringRule(rule.id, { pointValue: rule.pointValue, enabled: rule.enabled }).subscribe({
      next: () => this.show('Rule updated'),
      error: (e) => this.show(e.error?.error || 'Failed'),
    });
  }

  getMemberNames(team: any): string {
    if (!team.members || team.members.length === 0) return 'No members';
    return team.members.map((m: any) => m.displayName).join(', ');
  }

  updatePredictionRule(rule: any) {
    this.api.updatePredictionScoringRule(rule.id, {
      correctWinnerPoints: rule.correctWinnerPoints,
      correctGamesBonus: rule.correctGamesBonus,
      connSmytheBonus: rule.connSmytheBonus,
    }).subscribe({
      next: () => this.show('Prediction rule updated'),
      error: (e) => this.show(e.error?.error || 'Failed'),
    });
  }

  getRoundLabel(roundNumber: number): string {
    switch (roundNumber) {
      case 0: return 'Conn Smythe';
      case 1: return 'Round 1';
      case 2: return 'Round 2';
      case 3: return 'Conf. Finals';
      case 4: return 'Stanley Cup';
      default: return 'Round ' + roundNumber;
    }
  }
}
