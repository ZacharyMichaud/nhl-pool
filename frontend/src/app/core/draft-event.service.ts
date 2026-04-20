import { Injectable, OnDestroy } from '@angular/core';
import { Subject } from 'rxjs';
import { Client, IFrame, IMessage } from '@stomp/stompjs';
import { environment } from '../../environments/environment';

export interface DraftPickEvent {
  pickNumber: number;
  playerId: number;
  playerFirstName: string;
  playerLastName: string;
  playerPosition: string;
  playerTeamAbbrev: string;
  playerHeadshotUrl: string;
  teamId: number;
  teamName: string;
}

@Injectable({ providedIn: 'root' })
export class DraftEventService implements OnDestroy {
  private client: Client | null = null;
  private pickSubject = new Subject<DraftPickEvent>();
  private statsUpdateSubject = new Subject<number>();

  /** Consecutive failure tracking for exponential backoff */
  private failureCount = 0;
  private readonly MAX_FAILURES = 10;
  private readonly BASE_DELAY_MS = 5_000;
  private readonly MAX_DELAY_MS = 60_000;

  /** Observable that emits every time any user makes a draft pick. */
  readonly picks$ = this.pickSubject.asObservable();

  /** Observable that emits (with a timestamp) whenever the backend syncs player stats. */
  readonly statsUpdated$ = this.statsUpdateSubject.asObservable();

  /**
   * Connect to the backend via native WebSocket + STOMP.
   * Safe to call multiple times — only creates one connection.
   */
  connect() {
    if (this.client?.active) return;

    this.failureCount = 0;

    this.client = new Client({
      brokerURL: this.resolveWsUrl(),
      reconnectDelay: this.BASE_DELAY_MS,
      heartbeatIncoming: 10_000,
      heartbeatOutgoing: 10_000,

      onConnect: () => {
        // Successful connection — reset backoff
        this.failureCount = 0;
        if (this.client) this.client.reconnectDelay = this.BASE_DELAY_MS;

        this.client!.subscribe('/topic/draft-picks', (msg: IMessage) => {
          try {
            const event: DraftPickEvent = JSON.parse(msg.body);
            this.pickSubject.next(event);
          } catch {
            // ignore malformed messages
          }
        });

        this.client!.subscribe('/topic/stats-updated', (msg: IMessage) => {
          try {
            const ts = Number(msg.body);
            this.statsUpdateSubject.next(ts);
          } catch {
            // ignore malformed messages
          }
        });
      },

      // Intercept the raw WebSocket error so the browser console isn't spammed
      onWebSocketError: (_error: Event) => {
        this.handleFailure('WebSocket error');
      },

      onStompError: (_frame: IFrame) => {
        this.handleFailure('STOMP error');
      },

      onDisconnect: () => {
        this.handleFailure('disconnected');
      },
    });

    this.client.activate();
  }

  disconnect() {
    this.client?.deactivate();
    this.client = null;
    this.failureCount = 0;
  }

  ngOnDestroy() {
    this.disconnect();
  }

  // ── Private helpers ────────────────────────────────────────────────────

  private handleFailure(reason: string) {
    this.failureCount++;

    if (this.failureCount >= this.MAX_FAILURES) {
      console.warn(
        `[DraftEventService] Real-time updates unavailable (${reason}) after ` +
        `${this.MAX_FAILURES} attempts — stopping reconnection. ` +
        `Draft will rely on polling.`
      );
      // Fully deactivate so the browser stops firing connection errors
      this.client?.deactivate();
      this.client = null;
      return;
    }

    // Exponential backoff: 5s → 10s → 20s → 40s → 60s (capped)
    const nextDelay = Math.min(
      this.BASE_DELAY_MS * Math.pow(2, this.failureCount - 1),
      this.MAX_DELAY_MS
    );

    if (this.client) {
      this.client.reconnectDelay = nextDelay;
    }
  }

  /**
   * Converts the HTTP(S) API base URL to a ws:// / wss:// WebSocket URL.
   *
   * Examples:
   *   http://localhost:8080/api  →  ws://localhost:8080/ws-native
   *   https://myapp.onrender.com/api  →  wss://myapp.onrender.com/ws-native
   */
  private resolveWsUrl(): string {
    const apiUrl = environment.apiUrl;
    const base = apiUrl.replace(/\/api$/, '');
    const wsBase = base.replace(/^https:\/\//, 'wss://').replace(/^http:\/\//, 'ws://');
    return `${wsBase}/ws-native`;
  }
}
