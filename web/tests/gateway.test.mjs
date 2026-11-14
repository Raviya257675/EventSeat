import test from 'node:test';
import assert from 'node:assert/strict';
import net from 'node:net';
import { once } from 'node:events';
import { WebSocket } from 'ws';
import { createGateway } from '../gateway/server.mjs';
import { client } from './helpers.mjs';

test('HTTP isolation; persistent distinct TCP sessions; interleaved heartbeat, broadcasts and FIFO replies', async t => {
  const sockets = new Set(), commands = []; let pongs = 0;
  const backend = net.createServer(socket => {
    sockets.add(socket); socket.on('close',() => sockets.delete(socket)); socket.setEncoding('utf8'); let buffer = '';
    socket.write('PING:test-challenge\nCLIENT_STATS:2\n');
    socket.on('data', chunk => { buffer += chunk; let index; while ((index = buffer.indexOf('\n')) >= 0) {
      const line = buffer.slice(0,index); buffer = buffer.slice(index+1); commands.push(line);
      if (line === 'PONG:test-challenge') { pongs++; socket.write('LATENCY:1\n'); }
      if (line === 'GET_EVENTS') { socket.write('SEAT_UPDATE:E001:A1:HELD\nEV'); setTimeout(() => socket.write('ENTS:E001|Concert|Hall|2026-12-12;\n'),20); }
      if (line === 'GET_SEATS:E001') socket.write('SEATS:E001:A1=HELD,A2=AVAILABLE,\n');
    }});
  }).listen(0,'127.0.0.1'); await once(backend,'listening');
  const gateway = createGateway({tcpPort:backend.address().port}); gateway.server.listen(0,'127.0.0.1'); await once(gateway.server,'listening');
  t.after(async () => { await gateway.close(); for (const s of sockets) s.destroy(); await new Promise(r => backend.close(r)); });
  const port = gateway.server.address().port;
  assert.equal((await fetch(`http://127.0.0.1:${port}/`)).status,200);
  for (const path of ['/db.properties','/src/main/resources/db.properties','/.env','/package.json','/%2e%2e/%2e%2e/pom.xml']) assert.equal((await fetch(`http://127.0.0.1:${port}${path}`)).status,404);
  const a = await client(port), b = await client(port); assert.equal(sockets.size,2);
  const [events,seats] = await Promise.all([a.request('events'),a.request('seats',{eventId:'E001'})]);
  assert.equal(events.events[0].name,'Concert'); assert.equal(seats.seats.length,2);
  assert.ok(pongs >= 2); await a.wait(m => m.type === 'seat');
  assert.equal((await a.request('book',{eventId:'E001',seat:'A2',name:'Ada'})).kind,'error');
  assert.ok(!commands.some(c => c.startsWith('BOOK:')));
  a.close(); await once(a.ws,'close');
  await new Promise(r => setTimeout(r,50)); assert.equal(sockets.size,1);
  b.close();
  const denied = new WebSocket(`ws://127.0.0.1:${port}/ws`,{origin:'http://evil.example'});
  await new Promise(resolve => denied.on('error', error => { assert.match(error.message,/403/); resolve(); }));
});
test('timeout ends ambiguous session; invalid input closes session; reconnect gets fresh backend', async t => {
  const sockets = new Set();
  const backend = net.createServer(s => { sockets.add(s); s.on('close',() => sockets.delete(s)); }).listen(0,'127.0.0.1'); await once(backend,'listening');
  const gateway = createGateway({tcpPort:backend.address().port,requestTimeout:100}); gateway.server.listen(0,'127.0.0.1'); await once(gateway.server,'listening');
  t.after(async () => { await gateway.close(); for (const s of sockets) s.destroy(); await new Promise(r => backend.close(r)); });
  const port = gateway.server.address().port, a = await client(port);
  a.ws.send(JSON.stringify({id:'timeout',op:'events'})); assert.match((await a.wait(m => m.type === 'offline')).message,/timed out/); await once(a.ws,'close');
  const b = await client(port); b.ws.send(JSON.stringify({id:'bad',op:'raw',command:'EXIT'})); assert.match((await b.wait(m => m.type === 'offline')).message,/Invalid request/);
});
