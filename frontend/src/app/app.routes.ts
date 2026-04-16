import { Routes } from '@angular/router';
import { authGuard, adminGuard } from './core/auth.guard';

export const routes: Routes = [
  { path: 'login', loadComponent: () => import('./features/login/login.component').then(m => m.LoginComponent) },
  { path: 'register', loadComponent: () => import('./features/register/register.component').then(m => m.RegisterComponent) },
  { path: '', canActivate: [authGuard], loadComponent: () => import('./features/dashboard/dashboard.component').then(m => m.DashboardComponent) },
  { path: 'draft', canActivate: [authGuard], loadComponent: () => import('./features/draft/draft.component').then(m => m.DraftComponent) },
  { path: 'standings', canActivate: [authGuard], loadComponent: () => import('./features/standings/standings.component').then(m => m.StandingsComponent) },
  { path: 'my-team', canActivate: [authGuard], loadComponent: () => import('./features/my-team/my-team.component').then(m => m.MyTeamComponent) },
  { path: 'predictions', canActivate: [authGuard], loadComponent: () => import('./features/predictions/predictions.component').then(m => m.PredictionsComponent) },
  { path: 'admin', canActivate: [authGuard, adminGuard], loadComponent: () => import('./features/admin/admin.component').then(m => m.AdminComponent) },
  { path: '**', redirectTo: '' },
];
