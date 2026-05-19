import { Component, Input } from '@angular/core';
import { CommonModule } from '@angular/common';

/**
 * Compact points chip used in both the Teams accordion and the Series predictions table.
 *
 * @param total  Points earned (number), or null when the series is not yet finished /
 *               no prediction exists (renders a pending "–" dash).
 * @param compact  When true, omits the "pts" suffix — useful in tight table columns.
 */
@Component({
  selector: 'app-pred-pts-badge',
  standalone: true,
  imports: [CommonModule],
  template: `
    <span class="pred-pts-badge"
          [class.badge-earned]="total !== null && total > 0"
          [class.badge-zero]="total !== null && total === 0"
          [class.badge-pending]="total === null">
      {{ total !== null ? total + ' pts' : '–' }}
    </span>
  `,
  styles: [`
    .pred-pts-badge {
      display: inline-flex;
      align-items: center;
      justify-content: center;
      font-size: 11px;
      font-weight: 700;
      padding: 2px 8px;
      border-radius: 20px;
      white-space: nowrap;
      flex-shrink: 0;

      &.badge-earned {
        color: #4ade80;
        background: rgba(74, 222, 128, 0.12);
        border: 1px solid rgba(74, 222, 128, 0.2);
      }

      &.badge-zero {
        color: var(--text-muted);
        background: rgba(255, 255, 255, 0.04);
        border: 1px solid var(--border);
      }

      &.badge-pending {
        color: var(--text-muted);
        background: transparent;
        border: 1px solid var(--border);
        font-weight: 400;
      }
    }
  `],
})
export class PredPtsBadgeComponent {
  /** Points total, or null for pending/unknown. */
  @Input() total: number | null = null;
}
