// Run only against WebDatabaseFixture + a gateway pointing to its port.
import assert from 'node:assert/strict';
import { client } from './helpers.mjs';
const port = Number(process.env.TEST_WEB_PORT || 8081);
const a = await client(port), b = await client(port);
try {
  const events = await a.request('events');
  assert.equal(events.events[0].name,'Web integration concert','Refusing to mutate a non-fixture server');
  const fields = {eventId:'E001',seat:'A1'};
  assert.equal((await a.request('seats',{eventId:'E001'})).seats.length,3);
  await b.request('seats',{eventId:'E001'});
  assert.equal((await a.request('hold',fields)).kind,'hold');
  await b.wait(m => m.type === 'seat' && m.seat === 'A1' && m.status === 'HELD');
  assert.equal((await b.request('hold',fields)).kind,'error');
  assert.equal((await b.request('book',{...fields,name:'Other client'})).kind,'error');
  assert.equal((await a.request('release',fields)).kind,'released');
  await b.wait(m => m.type === 'seat' && m.seat === 'A1' && m.status === 'AVAILABLE');
  await a.request('hold',fields);
  const booked = await a.request('book',{...fields,name:'Web Integration Test'});
  assert.equal(booked.kind,'booked'); assert.match(booked.bookingId,/^[1-9]\d*$/);
  await b.wait(m => m.type === 'seat' && m.status === 'BOOKED');
  const found = await b.request('lookup',{bookingId:booked.bookingId,name:'Web Integration Test'});
  assert.equal(found.booking.status,'ACTIVE'); assert.equal(found.booking.seat,'A1');
  assert.equal((await b.request('lookup',{bookingId:booked.bookingId,name:'Wrong name'})).kind,'error');
  assert.equal((await b.request('cancel',{bookingId:booked.bookingId,name:'Web Integration Test'})).kind,'cancelled');
  await a.wait(m => m.type === 'seat' && m.status === 'AVAILABLE');
  assert.equal((await a.request('lookup',{bookingId:booked.bookingId,name:'Web Integration Test'})).booking.status,'CANCELLED');
  console.log('PASS: two WebSocket clients, owned holds, MySQL booking/lookup/cancellation and broadcasts');
  await a.request('hold',fields); await a.request('leave');
  assert.equal((await b.request('seats',{eventId:'E001'})).seats.find(s => s.id === 'A1').status,'AVAILABLE');
  await a.request('seats',{eventId:'E001'}); await a.request('hold',fields); a.close();
  await new Promise(r => setTimeout(r,150));
  assert.equal((await b.request('seats',{eventId:'E001'})).seats.find(s => s.id === 'A1').status,'AVAILABLE');
  const c = await client(port);
  try {
    await c.request('seats',{eventId:'E001'});
    const hold = await c.request('hold',fields);
    assert.ok(hold.remainingMs > 59000 && hold.remainingMs <= 60000);
    // Remove previously observed broadcasts before verifying actual expiry.
    b.messages.length = 0;
    console.log('Waiting for real 60-second hold expiry...');
    await b.wait(m => m.type === 'seat' && m.seat === 'A1' && m.status === 'AVAILABLE',65000);
    assert.equal((await c.request('book',{...fields,name:'Expired hold'})).kind,'error');
    await c.wait(m => m.type === 'latency');
    console.log('PASS: navigation/disconnect release, fresh session, real expiry and heartbeat survival');
  } finally { c.close(); }
} finally { a.close(); b.close(); }
