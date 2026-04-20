import { Component, computed, inject, OnDestroy, OnInit, signal } from '@angular/core';
import { CommonModule } from '@angular/common';
import { FormsModule } from '@angular/forms';
import { MatButtonModule } from '@angular/material/button';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatInputModule } from '@angular/material/input';
import { MatSnackBar, MatSnackBarModule } from '@angular/material/snack-bar';
import { RouterLink } from '@angular/router';
import { catchError, EMPTY } from 'rxjs';
import { ApiService } from '../../core/api.service';
import { DropdownComponent } from '../../shared/components/dropdown/dropdown.component';
import { DropdownOption } from '../../shared/components/dropdown/dropdown.types';

@Component({
  selector: 'app-admin',
  standalone: true,
  imports: [CommonModule, FormsModule, MatButtonModule, MatFormFieldModule, MatInputModule, MatSnackBarModule, DropdownComponent, RouterLink],
  templateUrl: './admin.component.html',
  styleUrl: './admin.component.scss',
})
export class AdminComponent implements OnInit, OnDestroy {
  private api = inject(ApiService);
  private snackBar = inject(MatSnackBar);

  private pollInterval: any;

  draftConfig = signal<any>(null);
  teams = signal<any[]>([]);
  users = signal<any[]>([]);
  rounds = signal<any[]>([]);
  scoringRules = signal<any[]>([]);
  predictionRules = signal<any[]>([]);

  playersPerTeam = 10;
  draftOrderTeamIds: number[] = [];
  newTeamName = '';
  assignTeamId: number | null = null;
  assignUserIds: number[] = [];

  // Inline rename state
  editingTeamId: number | null = null;
  editingTeamName = '';

  // ── Dropdown option lists ────────────────────────────────────────────────────

  teamDropdownOptions = computed<DropdownOption[]>(() =>
    this.teams().map(t => ({ value: t.id, label: t.name }))
  );

  userDropdownOptions = computed<DropdownOption[]>(() =>
    this.users().map(u => ({ value: u.id, label: `${u.displayName} (${u.username})` }))
  );

  readonly roundStatusOptions: DropdownOption[] = [
    { value: 'UPCOMING',  label: 'Upcoming' },
    { value: 'ACTIVE',    label: 'Active' },
    { value: 'COMPLETED', label: 'Completed' },
  ];

  // ─────────────────────────────────────────────────────────────────────────────

  ngOnInit() {
    this.loadAll();
    // Auto-refresh every 5 s so admin panel mirrors live draft state
    this.pollInterval = setInterval(() => this.loadAll(), 5000);
  }

  ngOnDestroy() {
    if (this.pollInterval) clearInterval(this.pollInterval);
  }

  loadAll() {
    this.api.getDraftConfig().pipe(catchError(e => { this.show('Config load failed: ' + (e.status || e.message)); return EMPTY; })).subscribe((c) => {
      this.draftConfig.set(c);
      this.playersPerTeam = c.playersPerTeam;
      const order: string = c.draftOrder || '';
      this.draftOrderTeamIds = order
        ? order.split(',').map((id: string) => parseInt(id.trim())).filter((id: number) => !isNaN(id))
        : [];
    });
    this.api.getTeams().pipe(catchError(e => { this.show('Teams load failed: ' + (e.status || e.message)); return EMPTY; })).subscribe((t) => this.teams.set(t));
    this.api.getUsers().pipe(catchError(e => { this.show('Users load failed: ' + (e.status || e.message)); return EMPTY; })).subscribe((u) => this.users.set(u));
    this.api.getRounds().pipe(catchError(e => { this.show('Rounds load failed: ' + (e.status || e.message)); return EMPTY; })).subscribe((r) => this.rounds.set(r));
    this.api.getScoringRules().pipe(catchError(e => { this.show('Rules load failed: ' + (e.status || e.message)); return EMPTY; })).subscribe((r: any) => {
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
    console.log('[Admin] syncStats() called — POSTing to /api/admin/sync/stats');
    this.api.syncStats().subscribe({
      next: (res) => {
        console.log('[Admin] syncStats() success:', res);
        this.show('Stats synced! Reloading data...');
        this.loadAll();
      },
      error: (e) => {
        console.error('[Admin] syncStats() error:', e);
        this.show(e.error?.error || e.message || 'Sync failed');
      },
    });
  }

  syncAllStats() {
    console.log('[Admin] syncAllStats() called — POSTing to /api/admin/sync/all-stats');
    this.api.syncAllStats().subscribe({
      next: (res) => {
        console.log('[Admin] syncAllStats() success:', res);
        this.show('All stats synced! Reloading data...');
        this.loadAll();
      },
      error: (e) => {
        console.error('[Admin] syncAllStats() error:', e);
        this.show(e.error?.error || e.message || 'Sync failed');
      },
    });
  }

  syncSeries() {
    console.log('[Admin] syncSeries() called — POSTing to /api/admin/sync/series');
    this.api.syncSeries().subscribe({
      next: (res) => {
        console.log('[Admin] syncSeries() success:', res);
        this.show('Series synced!');
        this.loadAll();
      },
      error: (e) => {
        console.error('[Admin] syncSeries() error:', e);
        this.show(e.error?.error || e.message || 'Sync failed');
      },
    });
  }

  saveDraftConfig() {
    const draftOrder = this.draftOrderTeamIds.join(',');
    this.api.updateDraftConfig({ playersPerTeam: this.playersPerTeam, draftOrder }).subscribe({
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

  startRename(team: any) {
    this.editingTeamId = team.id;
    this.editingTeamName = team.name;
  }

  cancelRename() {
    this.editingTeamId = null;
    this.editingTeamName = '';
  }

  saveRename(team: any) {
    const name = this.editingTeamName.trim();
    if (!name || name === team.name) { this.cancelRename(); return; }
    this.api.renameTeam(team.id, name).subscribe({
      next: () => { this.show('Team renamed!'); this.cancelRename(); this.loadAll(); },
      error: (e) => this.show(e.error?.error || 'Failed'),
    });
  }

  deleteTeam(team: any) {
    if (!confirm(`Delete team "${team.name}"? This will unlink all members and remove their draft picks.`)) return;
    this.api.deleteTeam(team.id).subscribe({
      next: () => { this.show(`Team "${team.name}" deleted.`); this.loadAll(); },
      error: (e) => this.show(e.error?.error || 'Failed to delete team'),
    });
  }

  assignMembers() {
    if (this.assignTeamId == null || this.assignUserIds.length === 0) return;
    this.api.assignMembers(this.assignTeamId, this.assignUserIds).subscribe({
      next: () => { this.show('Members assigned!'); this.assignTeamId = null; this.assignUserIds = []; this.loadAll(); },
      error: (e) => this.show(e.error?.error || 'Failed'),
    });
  }

  deleteUser(user: any) {
    if (!confirm(`Delete user "${user.displayName}" (@${user.username})? This cannot be undone.`)) return;
    this.api.deleteUser(user.id).subscribe({
      next: () => { this.show(`User "${user.displayName}" deleted.`); this.loadAll(); },
      error: (e) => this.show(e.error?.error || 'Failed to delete user'),
    });
  }

  updateRound(r: any) {
    this.api.updateRoundStatus(r.id, r.status).subscribe({
      next: () => this.show('Round updated'),
      error: (e) => this.show(e.error?.error || 'Failed'),
    });
  }

  onRoundStatusChange(r: any, val: string) {
    r.status = val;
    this.updateRound(r);
  }

  updateScoringRule(rule: any) {
    this.api.updateScoringRule(rule.id, { pointValue: rule.pointValue, enabled: rule.enabled }).subscribe({
      next: () => this.show('Rule updated'),
      error: (e) => this.show(e.error?.error || 'Failed'),
    });
  }

  getMemberNames(team: any): string {
    const members = this.users().filter((u: any) => u.team?.id === team.id);
    if (members.length === 0) return 'No members';
    return members.map((u: any) => u.displayName).join(', ');
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

  togglePredictionsLock() {
    this.api.lockPredictions().subscribe({
      next: (cfg: any) => {
        this.draftConfig.set(cfg);
        this.show(cfg.predictionsLocked ? '🔒 Predictions locked' : '🔓 Predictions unlocked');
      },
      error: (e: any) => this.show(e.error?.error || 'Failed'),
    });
  }

  toggleConnSmytheLock() {
    this.api.lockConnSmythe().subscribe({
      next: (cfg: any) => {
        this.draftConfig.set(cfg);
        this.show(cfg.connSmytheLocked ? '🔒 Conn Smythe locked' : '🔓 Conn Smythe unlocked');
      },
      error: (e: any) => this.show(e.error?.error || 'Failed'),
    });
  }
}
