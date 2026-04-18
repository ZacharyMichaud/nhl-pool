import { Injectable, signal, computed } from '@angular/core';
import { HttpClient } from '@angular/common/http';
import { Router } from '@angular/router';
import { tap } from 'rxjs';

export interface AuthResponse {
  token: string;
  userId: number;
  username: string;
  displayName: string;
  role: string;
  teamId: number | null;
}

@Injectable({ providedIn: 'root' })
export class AuthService {
  private readonly API = '/api/auth';

  private _user = signal<AuthResponse | null>(null);
  user = this._user.asReadonly();
  isLoggedIn = computed(() => !!this._user());
  isAdmin = computed(() => this._user()?.role === 'ADMIN');
  teamId = computed(() => this._user()?.teamId);

  constructor(private http: HttpClient, private router: Router) {
    const stored = localStorage.getItem('nhl_pool_user');
    if (stored) {
      try { this._user.set(JSON.parse(stored)); } catch { localStorage.removeItem('nhl_pool_user'); }
    }
  }

  login(username: string, password: string) {
    return this.http.post<AuthResponse>(`${this.API}/login`, { username, password }).pipe(
      tap(res => this.storeUser(res))
    );
  }

  register(username: string, password: string, displayName: string, adminSecret?: string) {
    return this.http.post<AuthResponse>(`${this.API}/register`, { username, password, displayName, adminSecret }).pipe(
      tap(res => this.storeUser(res))
    );
  }

  logout() {
    this._user.set(null);
    localStorage.removeItem('nhl_pool_user');
    this.router.navigate(['/login']);
  }

  /** Re-syncs teamId and other profile data without requiring a full re-login. */
  refreshProfile() {
    return this.http.get<AuthResponse>(`${this.API}/me`).pipe(
      tap(res => this.storeUser(res))
    );
  }

  getToken(): string | null {
    return this._user()?.token ?? null;
  }

  private storeUser(res: AuthResponse) {
    this._user.set(res);
    localStorage.setItem('nhl_pool_user', JSON.stringify(res));
  }
}
