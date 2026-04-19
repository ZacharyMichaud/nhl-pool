import { HttpInterceptorFn, HttpErrorResponse } from '@angular/common/http';
import { inject } from '@angular/core';
import { catchError, throwError } from 'rxjs';
import { AuthService } from './auth.service';

export const authInterceptor: HttpInterceptorFn = (req, next) => {
  const auth = inject(AuthService);
  const token = auth.getToken();

  const publicEndpoints = ['/api/auth/login', '/api/auth/register'];
  if (token && !publicEndpoints.some(ep => req.url.includes(ep))) {
    req = req.clone({ setHeaders: { Authorization: `Bearer ${token}` } });
  }

  return next(req).pipe(
    catchError((err: HttpErrorResponse) => {
      // Token expired or invalid — clear session and redirect to login
      if (err.status === 401 && !publicEndpoints.some(ep => req.url.includes(ep))) {
        auth.logout();
      }
      return throwError(() => err);
    })
  );
};
