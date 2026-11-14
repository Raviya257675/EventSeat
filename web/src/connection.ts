import type { Broadcast, Operation, Request, Result } from '../shared/protocol';
export type ConnectionState = { online: boolean; connecting: boolean; epoch: number; message: string; count: number | null; latency: number | null };
export class Connection {
  private socket?: WebSocket;
  private sequence = 0;
  private watchdog?: ReturnType<typeof setTimeout>;
  private listeners = new Set<() => void>();
  private updates = new Set<(event: Broadcast) => void>();
  private pending = new Map<string, { resolve: (value: Result) => void; reject: (error: Error) => void; timer: ReturnType<typeof setTimeout> }>();
  private state: ConnectionState = { online: false, connecting: false, epoch: 0, message: '', count: null, latency: null };
  snapshot = () => this.state;
  subscribe = (fn: () => void) => { this.listeners.add(fn); return () => { this.listeners.delete(fn); }; };
  onUpdate = (fn: (event: Broadcast) => void) => { this.updates.add(fn); return () => { this.updates.delete(fn); }; };
  private change(patch: Partial<ConnectionState>) { this.state = { ...this.state, ...patch }; this.listeners.forEach(fn => fn()); }
  private rejectAll(message: string) { this.pending.forEach(p => { clearTimeout(p.timer); p.reject(new Error(message)); }); this.pending.clear(); }
  connect() {
    this.disconnect();
    this.change({ connecting: true, message: 'Connecting to the Java server…', epoch: this.state.epoch + 1 });
    const ws = new WebSocket(`${location.protocol === 'https:' ? 'wss:' : 'ws:'}//${location.host}/ws`);
    this.socket = ws;
    const armWatchdog = (ms: number) => {
      clearTimeout(this.watchdog);
      this.watchdog = setTimeout(() => {
        if (this.socket !== ws) return;
        this.disconnect(); this.change({ message: 'Connection timed out. Reconnect and check any unconfirmed booking.' });
      }, ms);
    };
    armWatchdog(8000);
    ws.onmessage = event => {
      if (this.socket !== ws) return;
      armWatchdog(30000);
      try {
        const data = JSON.parse(event.data);
        if (data.type === 'ready') this.change({ online: true, connecting: false, message: '' });
        else if (data.type === 'offline') { this.rejectAll(data.message); this.change({ online: false, connecting: false, message: data.message, count: null, latency: null }); }
        else if (data.type === 'response') {
          const p = this.pending.get(data.id);
          if (p) { this.pending.delete(data.id); clearTimeout(p.timer); data.result.kind === 'error' ? p.reject(new Error(data.result.message)) : p.resolve(data.result); }
        } else if (data.type === 'stats') this.change({ count: data.count });
        else if (data.type === 'latency') this.change({ latency: data.ms });
        else if (data.type === 'seat') this.updates.forEach(fn => fn(data));
      } catch { this.disconnect(); this.change({ message: 'Invalid gateway response. Reconnect to refresh.' }); }
    };
    ws.onclose = () => {
      if (this.socket !== ws) return;
      clearTimeout(this.watchdog);
      const message = this.state.message || 'Connection lost. Unconfirmed operations may have completed. Look up a booking before retrying.';
      this.rejectAll(message); this.change({ online: false, connecting: false, message, count: null, latency: null });
    };
    ws.onerror = () => { if (this.socket === ws) this.change({ message: 'Cannot reach the gateway or Java server.' }); };
  }
  disconnect() {
    clearTimeout(this.watchdog);
    const ws = this.socket; this.socket = undefined; ws?.close();
    this.rejectAll('Session ended. Refresh availability; check any unconfirmed booking before retrying.');
    this.change({ online: false, connecting: false, count: null, latency: null });
  }
  request(op: Operation, fields: Omit<Partial<Request>, 'id' | 'op'> = {}): Promise<Result> {
    if (!this.state.online || this.socket?.readyState !== WebSocket.OPEN) return Promise.reject(new Error('Connect to the server first.'));
    const id = String(++this.sequence);
    return new Promise((resolve, reject) => {
      const timer = setTimeout(() => { this.disconnect(); this.change({ message: 'Request timed out. Check the booking before retrying.' }); }, 15000);
      this.pending.set(id, { resolve, reject, timer });
      this.socket!.send(JSON.stringify({ id, op, ...fields }));
    });
  }
}
export const connection = new Connection();
