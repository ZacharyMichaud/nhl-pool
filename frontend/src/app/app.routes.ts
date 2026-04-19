import { Routes } from '@angular/router';
import { authGuard, adminGuard } from './core/auth.guard';

export const routes: Routes = [
  { path: 'login',    loadComponent: () => import('./features/login/login.component').then(m => m.LoginComponent) },
  { path: 'register', loadComponent: () => import('./features/register/register.component').then(m => m.RegisterComponent) },

  // Landing = Standings
  { path: '', canActivate: [authGuard], loadComponent: () => import('./features/standings/standings.component').then(m => m.StandingsComponent) },

  { path: 'series',   canActivate: [authGuard], loadComponent: () => import('./features/series/series.component').then(m => m.SeriesComponent) },
  { path: 'players',  canActivate: [authGuard], loadComponent: () => import('./features/players/players.component').then(m => m.PlayersComponent) },
  { path: 'teams',    canActivate: [authGuard], loadComponent: () => import('./features/my-team/my-team.component').then(m => m.MyTeamComponent) },
  { path: 'draft',    canActivate: [authGuard], loadComponent: () => import('./features/draft/draft.component').then(m => m.DraftComponent) },
  { path: 'admin',    canActivate: [authGuard, adminGuard], loadComponent: () => import('./features/admin/admin.component').then(m => m.AdminComponent) },

  // Backwards-compat redirects
  { path: 'standings',   redirectTo: '',       pathMatch: 'full' },
  { path: 'my-team',     redirectTo: 'teams',  pathMatch: 'full' },
  { path: 'predictions', redirectTo: 'series', pathMatch: 'full' },

  { path: '**', redirectTo: '' },
];
