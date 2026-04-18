import { Injectable, OnDestroy } from '@angular/core';
import { Subject } from 'rxjs';
import { Client, IMessage } from '@stomp/stompjs';
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

  /** Observable that emits every time any user makes a draft pick. */
  readonly picks$ = this.pickSubject.asObservable();

  /**
   * Connect to the backend via native WebSocket + STOMP.
   * Safe to call multiple times — only creates one connection.
   */
  connect() {
    if (this.client?.active) return;

    this.client = new Client({
      // brokerURL uses ws:// / wss:// — native WebSocket, no SockJS needed
      brokerURL: this.resolveWsUrl(),
      reconnectDelay: 5000,
      onConnect: () => {
        this.client!.subscribe('/topic/draft-picks', (msg: IMessage) => {
          try {
            const event: DraftPickEvent = JSON.parse(msg.body);
            this.pickSubject.next(event);
          } catch {
            // ignore malformed messages
          }
        });
      },
    });

    this.client.activate();
  }

  disconnect() {
    this.client?.deactivate();
    this.client = null;
  }

  ngOnDestroy() {
    this.disconnect();
  }

  /**
   * Converts the HTTP(S) API base URL to a ws:// / wss:// WebSocket URL.
   *
   * Examples:
   *   http://localhost:8080/api  →  ws://localhost:8080/ws-native
   *   https://myapp.onrender.com/api  →  wss://myapp.onrender.com/ws-native
   */
  private resolveWsUrl(): string {
    const apiUrl = environment.apiUrl;            // e.g. "http://localhost:8080/api"
    const base = apiUrl.replace(/\/api$/, '');    // → "http://localhost:8080"
    const wsBase = base.replace(/^https:\/\//, 'wss://').replace(/^http:\/\//, 'ws://');
    return `${wsBase}/ws-native`;
  }
}
