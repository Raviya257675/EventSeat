import { useEffect, useRef, useState } from 'react';
import type { FormEvent } from 'react';
import { ArrowLeft, ArrowRight, CalendarDays, Check, Copy, Info, MapPin, Printer, Search, Ticket, Trash2, UserRound, X } from 'lucide-react';
import { QRCodeSVG } from 'qrcode.react';
import { eventDate, eventImage } from './data';
import type { Booking, Seat, SeatEvent } from './data';
import { validName } from '../shared/protocol';
import { connection } from './connection';
const errorText = (error: unknown) => error instanceof Error ? error.message : 'Request failed.';
export function SeatSelection({ event, onBooked }: { event: SeatEvent; onBooked: (booking: Booking) => void }) {
  const [seats,setSeats] = useState<Seat[]>([]), [held,setHeld] = useState<{ seat: string; deadline: number } | null>(null);
  const [loading,setLoading] = useState(true), [busy,setBusy] = useState(false), [name,setName] = useState(''), [error,setError] = useState(''), [now,setNow] = useState(performance.now());
  const locked = useRef(false), active = useRef(true);
  useEffect(() => {
    active.current = true; let mounted = true;
    const unsubscribe = connection.onUpdate(update => {
      if (update.type !== 'seat' || update.eventId !== event.id) return;
      setSeats(old => old.map(s => s.id === update.seat ? { ...s, status:update.status } : s));
      if (update.status !== 'HELD') setHeld(old => old?.seat === update.seat ? null : old);
    });
    connection.request('seats', { eventId:event.id }).then(r => { if (mounted && r.kind === 'seats') setSeats(r.seats); }).catch(e => { if (mounted) setError(errorText(e)); }).finally(() => { if (mounted) setLoading(false); });
    const tick = setInterval(() => setNow(performance.now()), 200);
    return () => { mounted = false; active.current = false; clearInterval(tick); unsubscribe(); if (connection.snapshot().online) void connection.request('leave').catch(() => {}); };
  }, [event.id]);
  const remaining = held ? Math.max(0,Math.ceil((held.deadline-now)/1000)) : 0;
  async function select(seat: string) {
    if (locked.current) return; locked.current = true; setBusy(true); setError('');
    const previous = held?.seat; setHeld(null);
    try {
      if (previous) await connection.request('release',{ eventId:event.id,seat:previous });
      if (!active.current) return;
      if (previous !== seat) {
        const result = await connection.request('hold',{ eventId:event.id,seat });
        if (active.current && result.kind === 'hold') { setNow(performance.now()); setHeld({ seat, deadline:performance.now()+result.remainingMs }); }
      }
    } catch (e) { if (active.current) setError(errorText(e)); }
    finally { locked.current = false; if (active.current) setBusy(false); }
  }
  async function confirm(e: FormEvent) {
    e.preventDefault(); if (locked.current || !held || remaining <= 0 || !validName(name)) return;
    locked.current = true; setBusy(true); setError('');
    try {
      const result = await connection.request('book',{ eventId:event.id,seat:held.seat,name });
      if (result.kind === 'booked' && active.current) onBooked({ id:result.bookingId, seat:result.seat,name:name.trim(),eventName:event.name,venue:event.venue,date:event.date,status:'ACTIVE',time:result.time });
    } catch (failure) { if (active.current) setError(errorText(failure)); }
    finally { locked.current = false; if (active.current) setBusy(false); }
  }
  const groups = new Map<string,Seat[]>();
  for (const seat of seats) { const row = seat.id.match(/^[A-Za-z]+/)?.[0] || 'Seats'; groups.set(row,[...(groups.get(row) || []),seat]); }
  return <div className="seat-page enter"><a className="back-link" href="#/events"><ArrowLeft size={16}/>Back to events</a><div className="event-detail-heading"><div className="event-heading-text"><span className="eyebrow">LIVE EVENT · {event.id}</span><h1>{event.name}</h1><div className="event-detail-meta"><span><CalendarDays size={16}/>{eventDate(event)}</span><span><MapPin size={16}/>{event.venue}</span></div></div><img className="event-heading-image" src={eventImage(event.id)} alt="Illustrative event artwork"/></div>
    <div className="booking-layout"><section className="seat-panel panel"><div className="panel-heading"><div><h2>Choose your seat</h2><p>One seat per booking. Availability updates live.</p></div><span className="availability"><span/>{seats.filter(s => s.status === 'AVAILABLE').length} available</span></div><div className="seat-legend"><span><i className="legend-available"/>Available</span><span><i className="legend-selected"/>Your hold</span><span><i className="legend-held"/>Held</span><span><i className="legend-booked"/>Booked</span></div>
      {loading ? <div className="empty-state">Loading seats…</div> : seats.length === 0 ? <div className="empty-state">No seats returned by the server.</div> : <div className="map-scroll"><div className="map-canvas"><div className="stage-area"><div className="stage">SEAT MAP</div><span>Schematic arrangement · server seat IDs</span></div><div className="seat-rows">{[...groups].map(([row,list]) => <div className="seat-row live-seat-row" key={row}><span className="row-name">{row}</span>{list.map(s => <button key={s.id} className={'seat ' + (s.status === 'BOOKED' ? 'booked ' : s.status === 'HELD' ? 'held ' : '') + (held?.seat === s.id && remaining > 0 ? 'chosen' : '')} aria-label={'Seat ' + s.id + ', ' + (held?.seat === s.id && remaining > 0 ? 'your hold' : s.status.toLowerCase())} aria-pressed={held?.seat === s.id && remaining > 0} disabled={busy || (s.status !== 'AVAILABLE' && held?.seat !== s.id)} onClick={() => select(s.id)}>{s.id}</button>)}</div>)}</div></div></div>}
      <div className="seat-map-tools"><span><Info size={14}/>Select a seat to request a 60-second server hold.</span></div></section>
      <aside className="booking-summary panel"><div className="summary-title"><Ticket size={20}/><h2>Your selection</h2></div><div className="selected-seat-list" aria-live="polite">{held && remaining > 0 ? <div className="selected-seat-item"><span className="selected-seat-number">{held.seat}</span><div><strong>Held for you</strong><span>{remaining}s remaining</span></div><button className="icon-button" aria-label="Release seat" disabled={busy} onClick={() => select(held.seat)}><X size={16}/></button></div> : <div className="selection-empty"><Ticket size={26}/><p>{held ? 'Your hold expired' : 'Your night starts here'}</p><span>Choose an available seat to continue.</span></div>}</div><form onSubmit={confirm}><label className="field-label" htmlFor="customer-name">Book under your name</label><div className="input-with-icon"><UserRound size={17}/><input id="customer-name" required maxLength={100} autoComplete="name" value={name} disabled={busy} onChange={e => setName(e.target.value)} placeholder="Your full name"/></div><p className="field-hint">Keep this name and the booking ID for lookup and cancellation.</p>{name && !validName(name) && <p className="form-error">Use 1–100 characters without colon, semicolon, pipe or control characters.</p>}{error && <p className="form-error" role="alert">{error}</p>}<button className="button button-primary confirm-button" disabled={busy || remaining <= 0 || !validName(name)}>{busy ? 'Waiting for server…' : 'Confirm booking'}<ArrowRight size={17}/></button><p className="demo-disclaimer"><Info size={14}/>No payment is collected. The server does not provide prices.</p></form></aside></div></div>;
}
function DigitalTicket({ booking, compact = false }: { booking: Booking; compact?: boolean }) {
  return <article className={'digital-ticket ' + (compact ? 'compact-ticket ' : '') + (booking.status === 'CANCELLED' ? 'cancelled-ticket' : '')} aria-label={'Digital ticket for ' + booking.eventName}><div className="ticket-art"><img src="/images/midnight.jpg" alt=""/><div/><span><Ticket size={20}/>EventSeat</span><span className="ticket-status">{booking.status === 'ACTIVE' ? 'CONFIRMED' : 'CANCELLED'}</span></div><div className="ticket-main"><span className="ticket-category">SERVER BOOKING</span><h2>{booking.eventName}</h2><p className="ticket-location"><MapPin size={14}/>{booking.venue}</p><div className="ticket-fields"><div><span>EVENT DATE</span><strong>{eventDate(booking)}</strong></div><div><span>SEAT</span><strong>{booking.seat}</strong><small>1 guest</small></div><div><span>BOOKED BY</span><strong>{booking.name}</strong></div></div><p className="field-hint">Booked: {booking.time || 'Timestamp not supplied'}</p></div><div className="ticket-stub"><div className="ticket-reference"><span>BOOKING REFERENCE</span><strong>{booking.id}</strong><small>Keep your ID and customer name</small></div><QRCodeSVG value={'EventSeat booking reference: ' + booking.id} size={78} bgColor="#f4f1fb" fgColor="#201b30" title={'Booking reference ' + booking.id}/></div></article>;
}
export function Confirmation({ booking }: { booking: Booking }) {
  const [note,setNote] = useState('');
  async function copy() { try { await navigator.clipboard.writeText(booking.id); setNote('Booking ID copied.'); } catch { setNote('Select the reference to copy it manually.'); } }
  return <div className="confirmation-page enter"><div className="confirmation-heading"><span className="confirmation-icon"><Check size={29}/></span><h1>You’re going!</h1><p>The Java server confirmed your booking.</p></div><div className="confirmation-ticket-wrap"><DigitalTicket booking={booking}/><div className="ticket-action-row"><button className="button button-outline" onClick={copy}><Copy size={16}/>Copy booking ID</button><button className="button button-outline" onClick={() => window.print()}><Printer size={16}/>Print ticket</button></div><p role="status">{note}</p><div className="confirmation-note"><Info size={18}/><p>This is the received confirmation. Look up the booking to check its current status. After refresh, use your ID and name on any connected client.</p></div></div><div className="confirmation-actions"><a className="button button-primary" href="#/events">Explore more events</a><a className="text-link" href={'#/bookings?ref=' + booking.id}>Manage this booking<ArrowRight size={16}/></a></div></div>;
}
export function FindBooking({ reference, online }: { reference: string; online: boolean }) {
  const [id,setId] = useState(reference), [name,setName] = useState(''), [booking,setBooking] = useState<Booking | null>(null), [error,setError] = useState(''), [busy,setBusy] = useState(false), [showCancel,setShowCancel] = useState(false);
  const locked = useRef(false), alive = useRef(true), dialog = useRef<HTMLDialogElement>(null);
  useEffect(() => { alive.current = true; return () => { alive.current = false; }; }, []);
  useEffect(() => { if (showCancel) dialog.current?.showModal(); else dialog.current?.close(); }, [showCancel]);
  async function search(e: FormEvent) {
    e.preventDefault(); if (locked.current || !validName(name) || !/^[1-9]\d{0,9}$/.test(id)) return;
    locked.current = true; setBusy(true); setError(''); setBooking(null);
    try { const r = await connection.request('lookup',{ bookingId:id,name }); if (alive.current && r.kind === 'booking') setBooking(r.booking); }
    catch (failure) { if (alive.current) setError(errorText(failure)); }
    finally { locked.current = false; if (alive.current) setBusy(false); }
  }
  async function cancel() {
    if (locked.current || !booking) return; locked.current = true; setBusy(true); setError('');
    try { const r = await connection.request('cancel',{ bookingId:booking.id,name:booking.name }); if (alive.current && r.kind === 'cancelled') { setBooking({ ...booking,status:'CANCELLED' }); setShowCancel(false); } }
    catch (failure) { if (alive.current) { setError(errorText(failure)); setShowCancel(false); } }
    finally { locked.current = false; if (alive.current) setBusy(false); }
  }
  return <div className="lookup-page enter"><a href="#/events" className="back-link"><ArrowLeft size={16}/>Back to events</a><div className="lookup-heading"><span className="eyebrow">YOUR TICKETS</span><h1>Find my booking<span className="heading-dot">.</span></h1><p>A quick look at your plans. Or a change of plans.</p></div><div className="lookup-layout"><div><form className="lookup-form panel" onSubmit={search}><h2>Let’s find your ticket</h2><p>Use the numeric ID and exact name from your confirmation.</p><label className="field-label" htmlFor="booking-id">Booking ID</label><div className="input-with-icon"><Ticket size={17}/><input id="booking-id" inputMode="numeric" pattern="[1-9][0-9]*" maxLength={10} value={id} disabled={busy} onChange={e => { setId(e.target.value); setBooking(null); }} required placeholder="e.g. 42"/></div><label className="field-label" htmlFor="lookup-name">Customer name</label><div className="input-with-icon"><UserRound size={17}/><input id="lookup-name" maxLength={100} required value={name} disabled={busy} onChange={e => { setName(e.target.value); setBooking(null); }} placeholder="Name used when booking"/></div>{error && <p className="form-error" role="alert">{error}</p>}<button className="button button-primary" disabled={!online || busy || !validName(name) || !/^[1-9]\d{0,9}$/.test(id)}>{busy ? 'Waiting for server…' : 'Find my booking'}<Search size={17}/></button></form><p className="lookup-hint"><Info size={16}/>Look up server bookings from any connected device. No bookings are stored in this browser.</p></div><section className="lookup-result" aria-live="polite" aria-label="Booking search result">{booking ? <><div className="booking-result-status">{booking.status === 'ACTIVE' ? 'Your booking is confirmed' : 'This booking has been cancelled'}</div><DigitalTicket booking={booking} compact/><div className="manage-row"><button className="button button-outline" onClick={() => window.print()}><Printer size={16}/>Print ticket</button>{booking.status === 'ACTIVE' && <button className="button button-danger-outline" disabled={!online || busy} onClick={() => setShowCancel(true)}><Trash2 size={15}/>Cancel booking</button>}</div><p className="field-hint">Status at last server response. Search again to refresh.</p></> : <div className="lookup-empty"><div className="ticket-illustration"><Ticket size={48}/></div><h2>Your next memory, on record.</h2><p>Your server booking will appear here.</p></div>}</section></div>
    <dialog ref={dialog} className="cancel-dialog" aria-labelledby="cancel-heading" onCancel={e => { e.preventDefault(); if (!busy) setShowCancel(false); }}><div className="dialog-content"><span className="cancel-icon"><Trash2 size={23}/></span><h2 id="cancel-heading">Cancel your booking?</h2><p>Seat {booking?.seat} for {booking?.eventName} will become available to other guests.</p><div className="dialog-actions"><button className="button button-outline" disabled={busy} onClick={() => setShowCancel(false)}>Keep my booking</button><button className="button button-danger" disabled={busy} onClick={cancel}>{busy ? 'Cancelling…' : 'Confirm cancellation'}</button></div></div></dialog></div>;
}
