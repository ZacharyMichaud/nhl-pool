import { Component, EventEmitter, Input, OnChanges, Output, inject } from '@angular/core';
import { CommonModule } from '@angular/common';
import { LiveGameService } from '../../../core/live-game.service';

/**
 * A reusable player card row + bottom-sheet detail modal.
 *
 * Renders: headshot | name + meta | G · A · GP · PTS inline
 * Click → opens a bottom-sheet with full playoff stats.
 *
 * Inputs:
 *  - player:       object with playoffGoals, playoffAssists, etc.
 *  - label:        optional overline label (e.g. "Conn Smythe")
 *  - eliminated:   dims the card
 *  - extraBadge:   optional badge text rendered next to the name (e.g. "🏆")
 */
@Component({
  selector: 'app-player-card',
  standalone: true,
  imports: [CommonModule],
  templateUrl: './player-card.component.html',
  styleUrl: './player-card.component.scss',
})
export class PlayerCardComponent implements OnChanges {
  @Input() player: any = null;
  /** Optional label shown above the name (e.g. "Conn Smythe") */
  @Input() label: string | null = null;
  @Input() eliminated = false;
  /** Extra small badge next to the name */
  @Input() extraBadge: string | null = null;
  /** CSS class modifier for card border/background (e.g. 'cs') */
  @Input() variant: 'default' | 'cs' = 'default';
  /**
   * Controlled mode: when true, the modal is forced open by the parent.
   * Parent must listen to (modalClosed) to clear state.
   */
  @Input() forceModalOpen = false;
  @Output() modalClosed = new EventEmitter<void>();

  protected liveGame = inject(LiveGameService);

  modalOpen = false;

  ngOnChanges() {
    if (this.forceModalOpen) this.modalOpen = true;
  }

  openModal() {
    if (this.player) this.modalOpen = true;
  }

  closeModal() {
    this.modalOpen = false;
    this.modalClosed.emit();
  }

  get pts(): number { return (this.player?.playoffGoals ?? 0) + (this.player?.playoffAssists ?? 0); }
  get ppg(): number { return this.player?.playoffPowerPlayGoals ?? 0; }
  get ppp(): number { return this.player?.playoffPowerPlayPoints ?? 0; }
  get toi(): string { return this.player?.playoffAvgToi || '-'; }
  get pgp(): string {
    const gp  = this.player?.playoffGamesPlayed ?? 0;
    const pts = this.player?.playoffPoints ?? this.pts;
    return gp > 0 ? (pts / gp).toFixed(2) : '-';
  }

  formatPosition(pos: string | null | undefined): string {
    if (!pos) return '';
    if (pos === 'L') return 'LW';
    if (pos === 'R') return 'RW';
    return pos;
  }
}
