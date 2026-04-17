import { HttpClient } from '@angular/common/http';
import { Injectable } from '@angular/core';
import { Observable } from 'rxjs';

// In production: Vercel proxies /api/* → Render (no CORS)
// In dev: ng serve uses proxy.conf.json → localhost:8080
const API = '/api';

@Injectable({ providedIn: 'root' })
export class ApiService {

  constructor(private http: HttpClient) {}

  // Players
  getAvailablePlayers(teamAbbrev?: string, position?: string): Observable<any[]> {
    let params: any = {};
    if (teamAbbrev) {
      params.teamAbbrev = teamAbbrev;
    }
    if (position) {
      params.position = position;
    }
    return this.http.get<any[]>(`${ API }/players/available`, { params });
  }

  searchPlayers(q: string): Observable<any[]> {
    return this.http.get<any[]>(`${ API }/players/search`, { params: { q } });
  }

  // Draft
  getDraftConfig(): Observable<any> { return this.http.get(`${ API }/draft/config`); }

  getDraftBoard(): Observable<any[]> { return this.http.get<any[]>(`${ API }/draft/board`); }

  getDraftOrder(): Observable<any[]> { return this.http.get<any[]>(`${ API }/draft/order`); }

  makePick(playerId: number): Observable<any> { return this.http.post(`${ API }/draft/pick`, { playerId }); }

  // Watchlist
  getWatchlist(): Observable<any[]> { return this.http.get<any[]>(`${ API }/draft/watchlist`); }

  addToWatchlist(playerId: number): Observable<void> {
    return this.http.post<void>(`${ API }/draft/watchlist/${ playerId }`, {});
  }

  removeFromWatchlist(playerId: number): Observable<void> {
    return this.http.delete<void>(`${ API }/draft/watchlist/${ playerId }`);
  }

  reorderWatchlist(playerIds: number[]): Observable<void> {
    return this.http.put<void>(`${ API }/draft/watchlist/reorder`, { playerIds });
  }

  // Teams
  getTeams(): Observable<any[]> { return this.http.get<any[]>(`${ API }/teams`); }

  // Standings
  getStandings(): Observable<any[]> { return this.http.get<any[]>(`${ API }/standings`); }

  // Predictions
  getSeries(roundNumber?: number): Observable<any[]> {
    const url = roundNumber ? `${ API }/predictions/series/round/${ roundNumber }` : `${ API }/predictions/series`;
    return this.http.get<any[]>(url);
  }

  getPredictions(roundNumber: number): Observable<any[]> {
    return this.http.get<any[]>(`${ API }/predictions/round/${ roundNumber }`);
  }

  submitPrediction(seriesId: number, predictedWinnerAbbrev: string, predictedGames: number): Observable<any> {
    return this.http.post(`${ API }/predictions`, { seriesId, predictedWinnerAbbrev, predictedGames });
  }

  // Admin
  syncRosters(): Observable<any> { return this.http.post(`${ API }/admin/sync/rosters`, {}); }

  syncStats(): Observable<any> { return this.http.post(`${ API }/admin/sync/stats`, {}); }

  syncAllStats(): Observable<any> { return this.http.post(`${ API }/admin/sync/all-stats`, {}); }

  syncSeries(): Observable<any> { return this.http.post(`${ API }/admin/sync/series`, {}); }

  getScoringRules(): Observable<any> { return this.http.get(`${ API }/admin/scoring-rules`); }

  updateScoringRule(id: number, data: any): Observable<any> {
    return this.http.put(`${ API }/admin/scoring-rules/${ id }`, data);
  }

  updatePredictionScoringRule(id: number, data: any): Observable<any> {
    return this.http.put(`${ API }/admin/prediction-scoring-rules/${ id }`, data);
  }

  createTeam(name: string): Observable<any> { return this.http.post(`${ API }/admin/teams`, { name }); }

  assignMembers(teamId: number, userIds: number[]): Observable<any> {
    return this.http.put(`${ API }/admin/teams/${ teamId }/members`, { userIds });
  }

  getUsers(): Observable<any[]> { return this.http.get<any[]>(`${ API }/admin/users`); }

  getRounds(): Observable<any[]> { return this.http.get<any[]>(`${ API }/admin/rounds`); }

  updateRoundStatus(id: number, status: string): Observable<any> {
    return this.http.put(`${ API }/admin/rounds/${ id }/status`, { status });
  }

  getBracket(): Observable<any> { return this.http.get(`${ API }/admin/bracket`); }

  startDraft(): Observable<any> { return this.http.post(`${ API }/draft/start`, {}); }

  resetDraft(): Observable<any> { return this.http.post(`${ API }/draft/reset`, {}); }

  updateDraftConfig(data: any): Observable<any> { return this.http.put(`${ API }/draft/config`, data); }
}
