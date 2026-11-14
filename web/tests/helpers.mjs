import { WebSocket } from 'ws';
export async function client(port) {
  const ws = new WebSocket(`ws://127.0.0.1:${port}/ws`, { origin:`http://127.0.0.1:${port}` });
  const messages = [], waiters = []; let id = 0;
  ws.on('message', raw => { const message = JSON.parse(raw); messages.push(message); for (const notify of [...waiters]) notify(); });
  ws.on('error', () => {});
  function wait(predicate, timeout = 5000) {
    return new Promise((resolve,reject) => {
      const timer = setTimeout(() => { cleanup(); reject(new Error('Message timeout: ' + predicate)); }, timeout);
      const cleanup = () => { clearTimeout(timer); const index = waiters.indexOf(check); if (index >= 0) waiters.splice(index,1); };
      const check = () => { const index = messages.findIndex(predicate); if (index >= 0) { const [value] = messages.splice(index,1); cleanup(); resolve(value); } };
      waiters.push(check); check();
    });
  }
  await wait(m => m.type === 'ready');
  return { ws, messages, wait, async request(op, fields = {}) { const requestId = String(++id); ws.send(JSON.stringify({ id:requestId,op,...fields })); return (await wait(m => m.type === 'response' && m.id === requestId)).result; }, close() { ws.close(); } };
}
