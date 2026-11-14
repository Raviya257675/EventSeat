import type { SeatEvent } from '../shared/protocol';
export type { SeatEvent, Booking, Seat } from '../shared/protocol';
// Decorative artwork only. Inventory and all event fields come from Java.
const artwork = ['/images/midnight.jpg','/images/jazz.jpg','/images/classical.jpg','/images/electronic.jpg','/images/theatre.jpg','/images/sundown.jpg'];
export function eventImage(id: string) { return artwork[Array.from(id).reduce((n,c) => n + c.charCodeAt(0), 0) % artwork.length]; }
export function eventDate(event: Pick<SeatEvent, 'date'>, options?: Intl.DateTimeFormatOptions) {
  const date = new Date(event.date + 'T12:00:00');
  return Number.isNaN(date.getTime()) ? event.date : new Intl.DateTimeFormat('en-GB', options ?? { day:'numeric', month:'short', year:'numeric' }).format(date);
}
