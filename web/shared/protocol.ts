// Shared typed boundary. Only this allowlist can become a TCP command.
export type Operation = 'events' | 'seats' | 'hold' | 'release' | 'leave' | 'book' | 'lookup' | 'cancel';
export type Request = { id: string; op: Operation; eventId?: string; seat?: string; name?: string; bookingId?: string };
export type SeatStatus = 'AVAILABLE' | 'HELD' | 'BOOKED';
export type SeatEvent = { id: string; name: string; venue: string; date: string };
export type Seat = { id: string; status: SeatStatus };
export type Booking = { id: string; seat: string; name: string; eventName: string; venue: string; date: string; status: 'ACTIVE' | 'CANCELLED'; time: string };
export type Result =
  | { kind: 'events'; events: SeatEvent[] }
  | { kind: 'seats'; eventId: string; seats: Seat[] }
  | { kind: 'hold'; eventId: string; seat: string; expiresAt: number; remainingMs: number }
  | { kind: 'released'; eventId: string; seat: string }
  | { kind: 'left' }
  | { kind: 'booked'; eventId: string; seat: string; bookingId: string; time: string }
  | { kind: 'booking'; booking: Booking }
  | { kind: 'cancelled'; bookingId: string; eventId: string; seat: string }
  | { kind: 'error'; message: string };
export type Broadcast = { type: 'seat'; eventId: string; seat: string; status: SeatStatus } | { type: 'stats'; count: number } | { type: 'latency'; ms: number };
export function validName(value: unknown): value is string {
  return typeof value === 'string' && value.trim().length > 0 && value.length <= 100 && !/[:;|\x00-\x1f\x7f]/.test(value);
}
function token(value: unknown, max: number): string {
  if (typeof value !== 'string' || value.length > max || !/^[A-Za-z0-9_-]+$/.test(value)) throw new Error('Invalid event or seat identifier.');
  return value;
}
export function command(value: unknown): { request: Request; line: string } {
  if (!value || typeof value !== 'object' || Array.isArray(value)) throw new Error('Invalid request.');
  const r = value as Request;
  if (typeof r.id !== 'string' || !/^[A-Za-z0-9_-]{1,64}$/.test(r.id)) throw new Error('Invalid request ID.');
  const allowed: Record<Operation, string[]> = { events: [], seats: ['eventId'], hold: ['eventId','seat'], release: ['eventId','seat'], leave: [], book: ['eventId','seat','name'], lookup: ['bookingId','name'], cancel: ['bookingId','name'] };
  if (!Object.hasOwn(allowed, r.op)) throw new Error('Unsupported operation.');
  if (Object.keys(r).some(k => !['id','op',...allowed[r.op]].includes(k))) throw new Error('Unsupported request field.');
  let suffix = '';
  if (allowed[r.op].includes('eventId')) suffix += ':' + token(r.eventId, 20);
  if (allowed[r.op].includes('seat')) suffix += ':' + token(r.seat, 10);
  if (allowed[r.op].includes('bookingId')) {
    if (typeof r.bookingId !== 'string' || !/^[1-9]\d{0,9}$/.test(r.bookingId) || Number(r.bookingId) > 2147483647) throw new Error('Enter a positive numeric booking ID.');
    suffix += ':' + r.bookingId;
  }
  if (allowed[r.op].includes('name')) {
    if (!validName(r.name)) throw new Error('Enter a name of 1–100 characters without protocol separators or control characters.');
    suffix += ':' + r.name.trim();
  }
  const verbs = { events: 'GET_EVENTS', seats: 'GET_SEATS', hold: 'HOLD', release: 'RELEASE', leave: 'LEAVE_SEATS', book: 'BOOK', lookup: 'GET_BOOKING', cancel: 'CANCEL_BOOKING' };
  return { request: r, line: verbs[r.op] + suffix };
}
function status(value: string): SeatStatus {
  if (!['AVAILABLE','HELD','BOOKED'].includes(value)) throw new Error('Invalid seat status from server.');
  return value as SeatStatus;
}
export function broadcast(line: string): Broadcast | null {
  const p = line.split(':');
  if (p[0] === 'SEAT_UPDATE' && p.length === 4) return { type: 'seat', eventId: p[1], seat: p[2], status: status(p[3]) };
  if ((p[0] === 'CLIENT_STATS' || p[0] === 'LATENCY') && p.length === 2 && /^\d+$/.test(p[1])) return p[0] === 'CLIENT_STATS' ? { type: 'stats', count: Number(p[1]) } : { type: 'latency', ms: Number(p[1]) };
  return null;
}
export function response(line: string, r: Request): Result {
  const p = line.split(':');
  if (r.op === 'events' && line.startsWith('EVENTS:')) {
    return { kind: 'events', events: line.slice(7).split(';').filter(Boolean).map(row => {
      const parts = row.split('|');
      if (parts.length !== 4) throw new Error('Malformed event data.');
      return { id: parts[0], name: parts[1], venue: parts[2], date: parts[3] };
    }) };
  }
  if (r.op === 'seats' && p[0] === 'SEATS' && p[1] === r.eventId && p.length === 3) return { kind: 'seats', eventId: p[1], seats: p[2].split(',').filter(Boolean).map(row => { const [id,s] = row.split('='); return { id, status: status(s) }; }) };
  if (r.op === 'hold' && p[0] === 'HOLD_SUCCESS' && p[1] === r.eventId && p[2] === r.seat && /^\d+$/.test(p[3])) return { kind: 'hold', eventId: p[1], seat: p[2], expiresAt: Number(p[3]), remainingMs: Math.max(0, Number(p[3]) - Date.now()) };
  if (r.op === 'release' && p[0] === 'RELEASE_SUCCESS' && p[1] === r.eventId && p[2] === r.seat) return { kind: 'released', eventId: p[1], seat: p[2] };
  if (r.op === 'leave' && line === 'LEAVE_SUCCESS') return { kind: 'left' };
  if (r.op === 'book' && p[0] === 'BOOKING_SUCCESS' && p[1] === r.eventId && p[2] === r.seat && /^[1-9]\d*$/.test(p[3])) return { kind: 'booked', eventId: p[1], seat: p[2], bookingId: p[3], time: p.slice(4).join(':') };
  if (r.op === 'lookup' && p[0] === 'BOOKING_DETAILS' && p[1] === r.bookingId && p.length >= 9 && ['ACTIVE','CANCELLED'].includes(p[7])) return { kind: 'booking', booking: { id: p[1], seat: p[2], name: p[3], eventName: p[4], venue: p[5], date: p[6], status: p[7] as Booking['status'], time: p.slice(8).join(':') } };
  if (r.op === 'cancel' && p[0] === 'CANCEL_SUCCESS' && p[1] === r.bookingId && p.length === 4) return { kind: 'cancelled', bookingId: p[1], eventId: p[2], seat: p[3] };
  const errors: Record<string,string> = { DATABASE_ERROR: 'The server could not complete the database operation.', EVENT_NOT_FOUND: 'Event not found.', SEAT_NOT_FOUND: 'Seat not found.', BOOKING_NOT_FOUND: 'No booking matches that ID and customer name.', ALREADY_CANCELLED: 'This booking is already cancelled.', ALREADY_BOOKED: 'This seat is already booked.', HOLD_DENIED: 'This seat cannot be held. Refresh availability and choose another seat.', RELEASE_DENIED: 'This seat is held by another client.', BOOKING_FAILED: 'Booking failed. The seat may be held by another client.', CANCEL_FAILED: 'Cancellation failed. Look up the booking again.', INVALID_BOOKING_REQUEST: 'Invalid booking request.', INVALID_CANCEL_REQUEST: 'Invalid cancellation request.', INVALID_CUSTOMER_NAME: 'Invalid customer name.', INVALID_HOLD_REQUEST: 'Invalid hold request.' };
  if (Object.hasOwn(errors,p[0])) return { kind: 'error', message: errors[p[0]] };
  throw new Error('Unexpected response from Java server.');
}
