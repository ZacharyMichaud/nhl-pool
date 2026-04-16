import { Component, inject } from '@angular/core';
import { CommonModule } from '@angular/common';
import { FormsModule } from '@angular/forms';
import { Router, RouterLink } from '@angular/router';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatInputModule } from '@angular/material/input';
import { MatButtonModule } from '@angular/material/button';
import { AuthService } from '../../core/auth.service';

@Component({
  selector: 'app-register',
  standalone: true,
  imports: [CommonModule, FormsModule, RouterLink, MatFormFieldModule, MatInputModule, MatButtonModule],
  templateUrl: './register.component.html',
  styleUrl: './register.component.scss',
})
export class RegisterComponent {
  private auth = inject(AuthService);
  private router = inject(Router);

  username = '';
  displayName = '';
  password = '';
  adminSecret = '';
  error = '';
  loading = false;

  register() {
    this.loading = true;
    this.error = '';
    this.auth.register(this.username, this.password, this.displayName, this.adminSecret || undefined).subscribe({
      next: () => this.router.navigate(['/']),
      error: (err) => {
        this.error = err.error?.error || 'Registration failed';
        this.loading = false;
      },
    });
  }
}
