import { Component, computed, inject, OnInit, signal } from '@angular/core';
import { CommonModule } from '@angular/common';
import { RouterOutlet, RouterLink, RouterLinkActive } from '@angular/router';
import { AuthService } from './core/auth.service';
import { ApiService } from './core/api.service';
import { MobileNavComponent } from './shared/components/mobile-nav/mobile-nav.component';

@Component({
  selector: 'app-root',
  standalone: true,
  imports: [CommonModule, RouterOutlet, RouterLink, RouterLinkActive, MobileNavComponent],
  templateUrl: './app.component.html',
  styleUrl: './app.component.scss',
})
export class AppComponent implements OnInit {
  auth = inject(AuthService);
  private api = inject(ApiService);

  private draftConfig = signal<any>(null);

  draftActive    = computed(() => this.draftConfig()?.status === 'IN_PROGRESS');
  draftCompleted = computed(() => this.draftConfig()?.status === 'COMPLETED');

  ngOnInit(): void {
    this.loadConfig();
  }

  private loadConfig(): void {
    this.api.getDraftConfig().subscribe({
      next: cfg => this.draftConfig.set(cfg),
      error: () => {}
    });
  }
}
