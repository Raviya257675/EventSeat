import http from 'node:http';
import net from 'node:net';
import { readFile } from 'node:fs/promises';
import { resolve, extname, sep } from 'node:path';
import { fileURLToPath } from 'node:url';
import { WebSocketServer, WebSocket } from 'ws';
import { command, response, broadcast } from '../shared/protocol.ts';

export function createGateway({ tcpPort = 5000, staticDir = fileURLToPath(new URL('../dist/', import.meta.url)), requestTimeout = 12000, heartbeatInterval = 10000 } = {}) {
  const root = resolve(staticDir);
  const server = http.createServer(async (req, res) => {
    res.setHeader('X-Content-Type-Options', 'nosniff');
    res.setHeader('Referrer-Policy', 'no-referrer');
    res.setHeader('Content-Security-Policy', "default-src 'self'; img-src 'self' data:; font-src 'self'; style-src 'self' 'unsafe-inline'; script-src 'self'; connect-src 'self'; frame-ancestors 'none'; base-uri 'self'; form-action 'self'");
    res.setHeader('Cache-Control', 'no-store');
    if (!['GET','HEAD'].includes(req.method)) { res.writeHead(405).end(); return; }
    if (req.url === '/health') { res.setHeader('Content-Type','application/json'); res.end(JSON.stringify({ gateway: 'ok' })); return; }
    try {
      const pathname = decodeURIComponent(new URL(req.url, 'http://localhost').pathname);
      if (pathname.includes('\\') || pathname.split('/').some(p => p.startsWith('.'))) throw new Error();
      const path = resolve(root, '.' + (pathname === '/' ? '/index.html' : pathname));
      if (!path.startsWith(root + sep)) throw new Error();
      const mime = { '.html':'text/html; charset=utf-8','.js':'text/javascript; charset=utf-8','.css':'text/css; charset=utf-8','.jpg':'image/jpeg','.png':'image/png','.svg':'image/svg+xml','.woff':'font/woff','.woff2':'font/woff2' }[extname(path)];
      if (!mime) throw new Error();
      const data = await readFile(path);
      res.setHeader('Content-Type',mime); res.end(req.method === 'HEAD' ? undefined : data);
    } catch { res.writeHead(404).end('Not found'); }
  });
  const wss = new WebSocketServer({ noServer: true, maxPayload: 2048, perMessageDeflate: false });
  server.on('upgrade', (req, socket, head) => {
    // Same-origin only. No caller-selected backend address or raw command endpoint.
    if (req.url !== '/ws' || req.headers.origin !== `http://${req.headers.host}` || wss.clients.size >= 100) { socket.end('HTTP/1.1 403 Forbidden\r\nConnection: close\r\n\r\n'); return; }
    wss.handleUpgrade(req, socket, head, ws => wss.emit('connection',ws));
  });
  wss.on('connection', ws => {
    const tcp = net.createConnection({ host: '127.0.0.1', port: tcpPort });
    tcp.setEncoding('utf8'); tcp.setNoDelay(true);
    let closed = false, ready = false, buffer = '', pending = null, queue = [], timer;
    let pong = true, allowance = 30, refill = Date.now(), held = null;
    const ids = new Set();
    const send = data => { if (ws.readyState === WebSocket.OPEN) { if (ws.bufferedAmount > 1024 * 1024) { shutdown('Slow browser connection.'); return; } ws.send(JSON.stringify(data)); } };
    const shutdown = message => {
      if (closed) return;
      closed = true; clearTimeout(timer); clearInterval(pulse); queue = []; held = null;
      if (message) send({ type: 'offline', message });
      tcp.destroy(); ws.close(1011, 'Session ended');
    };
    const pulse = setInterval(() => {
      if (!pong) { shutdown('Browser heartbeat timed out.'); ws.terminate(); return; }
      pong = false; ws.ping();
      send({ type: 'heartbeat' });
    }, heartbeatInterval);
    ws.on('pong', () => { pong = true; });
    timer = setTimeout(() => shutdown('Cannot connect to Java server.'), 4000);
    const pump = () => {
      if (!ready || pending || closed || !queue.length) return;
      pending = queue.shift();
      // Existing Java protocol allows legacy booking without a hold. Web UI requires its own live hold.
      if (pending.request.op === 'book' && (!held || held.eventId !== pending.request.eventId || held.seat !== pending.request.seat || held.expiresAt <= Date.now())) {
        send({ type:'response', id:pending.request.id, result:{ kind:'error', message:'Select and hold an available seat before booking.' } }); pending = null; pump(); return;
      }
      tcp.write(pending.line + '\n');
      timer = setTimeout(() => shutdown('Request timed out. The outcome may be unknown; look up any booking before retrying.'), requestTimeout);
    };
    tcp.on('connect', () => { if (closed) return; clearTimeout(timer); ready = true; send({ type:'ready' }); pump(); });
    tcp.on('data', chunk => {
      if (closed) return;
      buffer += chunk;
      if (buffer.length > 262144) { shutdown('Java response too large.'); return; }
      let newline;
      while ((newline = buffer.indexOf('\n')) >= 0 && !closed) {
        const line = buffer.slice(0,newline).replace(/\r$/, ''); buffer = buffer.slice(newline + 1);
        if (line.startsWith('PING:')) { tcp.write('PONG:' + line.slice(5) + '\n'); continue; }
        try {
          const event = broadcast(line);
          if (event) {
            if (event.type === 'seat' && held && event.eventId === held.eventId && event.seat === held.seat && event.status !== 'HELD') held = null;
            send(event); continue;
          }
          if (!pending) throw new Error('Unexpected reply');
          const result = response(line,pending.request);
          clearTimeout(timer);
          if (result.kind === 'hold') held = result;
          if (['released','left','events','booked'].includes(result.kind) || (result.kind === 'seats' && held?.eventId !== result.eventId)) held = null;
          send({ type:'response', id:pending.request.id, result }); pending = null; pump();
        } catch { shutdown('Invalid or out-of-order response from Java server.'); }
      }
    });
    tcp.on('error', () => shutdown('Java server unavailable. Reconnect after the server is running.'));
    tcp.on('close', () => shutdown('Java server disconnected. Unconfirmed operations may have completed; look up your booking before retrying.'));
    ws.on('error', () => shutdown()); ws.on('close', () => shutdown());
    ws.on('message', (raw, binary) => {
      if (closed) return;
      if (Date.now() - refill > 1000) { allowance = 30; refill = Date.now(); }
      if (--allowance < 0 || queue.length >= 16 || ids.size >= 10000) { shutdown('Too many requests.'); return; }
      try {
        if (binary) throw new Error();
        const item = command(JSON.parse(raw.toString()));
        if (ids.has(item.request.id)) throw new Error();
        ids.add(item.request.id); queue.push(item); pump();
      } catch { shutdown('Invalid request. Only supported booking operations are allowed.'); }
    });
  });
  return { server, wss, async close() { for (const ws of wss.clients) ws.terminate(); await new Promise(resolve => wss.close(resolve)); await new Promise(resolve => server.close(resolve)); } };
}
if (process.argv[1] && resolve(process.argv[1]) === fileURLToPath(import.meta.url)) {
  const port = Number(process.env.PORT || 8080), host = process.env.WEB_HOST || '127.0.0.1';
  const gateway = createGateway();
  gateway.server.listen(port, host, () => console.log(`EventSeat Web: http://${host}:${port} (Java TCP: 127.0.0.1:5000)`));
  gateway.server.on('error', error => { console.error(error.message); process.exitCode = 1; });
  for (const signal of ['SIGINT','SIGTERM']) process.on(signal, () => gateway.close().then(() => process.exit()));
}
