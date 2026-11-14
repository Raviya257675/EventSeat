import { useEffect, useRef, useState } from 'react';
import { ArrowUpRight, CalendarDays, Info, MapPin, Search, Ticket } from 'lucide-react';
import { eventDate, eventImage } from './data';
import type { Booking, SeatEvent } from './data';
import { connection } from './connection';
import { useConnection, useRoute } from './hooks';
import { SeatSelection, Confirmation, FindBooking } from './BookingViews';
import './print.css';
export const errorText = (error: unknown) => error instanceof Error ? error.message : 'The server could not complete this request.';
function Brand() { return <a className="brand" href="#/"><span className="brand-icon"><Ticket size={24}/></span>Event<span>Seat</span></a>; }
export default function App() {
  const route = useRoute(), status = useConnection();
  const [events,setEvents] = useState<SeatEvent[]>([]), [loading,setLoading] = useState(true), [error,setError] = useState('');
  const [ticket,setTicket] = useState<Booking | null>(null);
  const main = useRef<HTMLElement>(null);
  useEffect(() => { connection.connect(); return () => connection.disconnect(); }, []);
  useEffect(() => {
    let active = true;
    if (!status.online) { setEvents([]); setLoading(false); return; }
    setLoading(true); setError('');
    connection.request('events').then(r => { if (active && r.kind === 'events') setEvents(r.events); }).catch(e => { if (active) setError(errorText(e)); }).finally(() => { if (active) setLoading(false); });
    return () => { active = false; };
  }, [status.online,status.epoch]);
  useEffect(() => { window.scrollTo(0,0); main.current?.focus(); document.title = 'EventSeat — Live booking'; }, [route]);
  const [path,query = ''] = route.split('?');
  const selected = events.find(e => path === '/event/' + encodeURIComponent(e.id));
  let page;
  if (path === '/' || path === '/events') page = <Discover events={events} loading={loading || status.connecting} online={status.online} error={error} hero={path === '/'} />;
  else if (path === '/bookings') page = <FindBooking key={query + ':' + status.epoch + ':' + status.online} reference={new URLSearchParams(query).get('ref') || ''} online={status.online}/>;
  else if (path === '/ticket' && ticket) page = <Confirmation booking={ticket}/>;
  else if (selected && status.online) page = <SeatSelection key={selected.id + ':' + status.epoch} event={selected} onBooked={booking => { setTicket(booking); location.hash = '/ticket'; }}/>;
  else page = <div className="empty-state"><Ticket size={32}/><h1>{loading || status.connecting ? 'Loading event…' : 'Event unavailable'}</h1><p>{status.online ? 'Choose an event from the live collection.' : 'Reconnect to load current availability.'}</p><a className="button button-primary" href="#/events">Explore events</a></div>;
  return <><a href="#main-content" className="skip-link" onClick={e => { e.preventDefault(); main.current?.focus(); }}>Skip to content</a>
    <header className="site-header"><div className="header-inner page-width"><Brand/><nav className="main-nav" aria-label="Main navigation"><a className={path === '/' ? 'active' : ''} href="#/">Discover</a><a className={path.startsWith('/event') ? 'active' : ''} href="#/events">Events</a></nav><div className="header-actions"><span className="demo-badge"><span/>{status.online ? 'Live server' : 'Offline'}</span><a className="button button-outline header-booking" href="#/bookings"><Ticket size={16}/>Find my booking<ArrowUpRight size={15}/></a></div></div></header>
    <div className="connection-bar page-width" role="status"><span>{status.online ? 'Connected' : status.connecting ? 'Connecting…' : 'Disconnected'}</span><span>Online clients: {status.count ?? '—'}</span><span title="Measured by Java between the server and this browser's gateway TCP session">Gateway ↔ Java RTT: {status.latency === null ? 'waiting' : status.latency + ' ms'}</span>{!status.online && <button className="button button-outline" onClick={() => connection.connect()} disabled={status.connecting}>Reconnect</button>}</div>
    <main id="main-content" ref={main} tabIndex={-1} className="page-width">{status.message && <div className="alert error-alert" role="alert"><Info size={18}/>{status.message}</div>}{page}</main>
    <footer className="site-footer page-width"><div className="footer-top"><Brand/><p><Info size={14}/>Bookings are confirmed by the EventSeat Java server.</p><a href="#/events">Explore events<ArrowUpRight size={14}/></a></div><div className="footer-bottom"><span>© {new Date().getFullYear()} EventSeat</span><span>Good seats. Great memories.</span></div></footer></>;
}
function Discover({ events, loading, online, error, hero }: { events: SeatEvent[]; loading: boolean; online: boolean; error: string; hero: boolean }) {
  const [search,setSearch] = useState(''), [venue,setVenue] = useState(''), [sort,setSort] = useState('date');
  const filtered = events.filter(e => (e.name + ' ' + e.venue).toLowerCase().includes(search.toLowerCase()) && (!venue || venue === e.venue)).sort((a,b) => sort === 'name' ? a.name.localeCompare(b.name) : a.date.localeCompare(b.date));
  const featured = events[0];
  return <div className="discover-page enter">{hero && <section className="hero"><img className="hero-image" src="/images/midnight.jpg" alt="Decorative concert atmosphere"/><div className="hero-shade"/><div className="hero-content"><span className="hero-label"><span/>YOUR NEXT NIGHT OUT</span><h1>Book Your<br/><span>Experience.</span></h1><p>Find your event.<br/>Choose your seat. Make a memory.</p><a className="button button-primary" href="#/events">Explore events<ArrowUpRight size={18}/></a></div>{featured && <a className="hero-feature" href={'#/event/' + encodeURIComponent(featured.id)}><span><small>FROM THE LIVE COLLECTION</small><strong>{featured.name}</strong><span>{eventDate(featured)} · {featured.venue}</span></span><ArrowUpRight size={20}/></a>}</section>}
    <section className="events-section"><div className="section-heading"><div><h2>Find your next moment<span className="heading-dot">.</span></h2><p>Live events from the EventSeat server. Images are illustrative.</p></div><label className="sort-control"><span className="sr-only">Sort events</span><select value={sort} onChange={e => setSort(e.target.value)}><option value="date">Date, earliest first</option><option value="name">Name, A–Z</option></select></label></div>
      <div className="search-row"><label className="search-field"><Search size={19}/><span className="sr-only">Search events or venues</span><input placeholder="Search events or venues…" value={search} onChange={e => setSearch(e.target.value)}/></label><label className="filter-select"><MapPin size={17}/><span className="sr-only">Filter by venue</span><select value={venue} onChange={e => setVenue(e.target.value)}><option value="">All venues</option>{[...new Set(events.map(e => e.venue))].map(v => <option key={v}>{v}</option>)}</select></label></div>
      <div className="categories-row"><span className="category-tab selected">All events</span><span className="result-count">{filtered.length} events</span></div>
      {loading ? <div className="empty-state" role="status">Loading live events…</div> : error ? <div className="alert error-alert" role="alert">{error}</div> : !online ? <div className="empty-state"><h2>Connect to discover events</h2><p>Start the Java server and use Reconnect above.</p></div> : filtered.length === 0 ? <div className="empty-state"><h2>No events found</h2><p>{events.length ? 'Try another search or venue.' : 'The server returned an empty event collection.'}</p><button className="button button-outline" onClick={() => { setSearch(''); setVenue(''); }}>Clear filters</button></div> : <div className="event-grid">{filtered.map(e => <article key={e.id} className="event-card"><a className="event-image-link" href={'#/event/' + encodeURIComponent(e.id)} tabIndex={-1} aria-hidden="true"><img src={eventImage(e.id)} alt="" loading="lazy"/><span className="image-gradient"/><span className="event-category">Illustrative artwork</span></a><div className="event-card-body"><div className="availability"><span/>Live availability on seat map</div><h3><a href={'#/event/' + encodeURIComponent(e.id)}>{e.name}</a></h3><p className="event-meta"><MapPin size={15}/>{e.venue}</p><p className="event-meta"><CalendarDays size={15}/>{eventDate(e)}</p><div className="card-bottom"><span className="event-price">{e.id}</span><a className="card-action" href={'#/event/' + encodeURIComponent(e.id)}>View seats<ArrowUpRight size={17}/></a></div></div></article>)}</div>}
    </section></div>;
}
