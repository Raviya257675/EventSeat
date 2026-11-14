import test from 'node:test';
import assert from 'node:assert/strict';
import { command, response, broadcast, validName } from '../shared/protocol.ts';
test('operation allowlist rejects raw proxy commands, injection and invalid IDs', () => {
  for (const bad of [{op:'raw',line:'EXIT'},{op:'book',eventId:'E001',seat:'A1',name:'A\nEXIT'},{op:'lookup',bookingId:'ES-123',name:'Ada'},{op:'lookup',bookingId:'2147483648',name:'Ada'},{op:'seats',eventId:'../secret'}]) assert.throws(() => command({id:'1',...bad}));
  assert.equal(command({id:'1',op:'book',eventId:'E001',seat:'A1',name:' Ada '}).line,'BOOK:E001:A1:Ada');
  assert.equal(validName(''),false); assert.equal(validName('A:B'),false); assert.equal(validName('Nimali Perera'),true);
});
test('parse real events, variable seat maps, monitoring and timestamp colons', () => {
  assert.equal(response('EVENTS:E001|Concert|Hall|2026-12-12;', {op:'events'}).events[0].id,'E001');
  assert.equal(response('EVENTS:',{op:'events'}).events.length,0);
  assert.deepEqual(response('SEATS:E001:A1=AVAILABLE,B12=HELD,AA7=BOOKED,',{op:'seats',eventId:'E001'}).seats.map(s => s.id),['A1','B12','AA7']);
  assert.equal(response('BOOKING_SUCCESS:E001:A1:42:2026-11-14 12:34:56.123',{op:'book',eventId:'E001',seat:'A1'}).time,'2026-11-14 12:34:56.123');
  assert.equal(response('BOOKING_DETAILS:42:A1:Ada:Concert:Hall:2026-12-12:ACTIVE:2026-11-14 12:34:56.0',{op:'lookup',bookingId:'42'}).booking.time,'2026-11-14 12:34:56.0');
  assert.deepEqual(broadcast('SEAT_UPDATE:E001:A1:BOOKED'),{type:'seat',eventId:'E001',seat:'A1',status:'BOOKED'});
  assert.deepEqual(broadcast('CLIENT_STATS:3'),{type:'stats',count:3});
  assert.equal(response('DATABASE_ERROR',{op:'book'}).kind,'error');
  assert.throws(() => response('BOOKING_SUCCESS:E002:A1:42:time',{op:'book',eventId:'E001',seat:'A1'}));
});
