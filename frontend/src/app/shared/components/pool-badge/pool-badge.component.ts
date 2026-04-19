import { Component, Input } from '@angular/core';
import { CommonModule } from '@angular/common';

/**
 * Renders a coloured pill badge for a pool team.
 *
 * Usage:
 *   <app-pool-badge [name]="team.name" [colorIndex]="teamColorIndex(team.id)" />
 */
@Component({
  selector: 'app-pool-badge',
  standalone: true,
  imports: [CommonModule],
  templateUrl: './pool-badge.component.html',
  styleUrl: './pool-badge.component.scss',
})
export class PoolBadgeComponent {
  /** Display name of the pool team */
  @Input() name: string | null | undefined = '';

  /**
   * 0-based colour index (matches the $colors palette, mod 10).
   * Drives both the background tint and the text colour.
   */
  @Input() colorIndex: number = 0;
}
