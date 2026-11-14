import test from 'node:test';
import assert from 'node:assert/strict';
import { Connection } from '../src/connection.ts';
test('frontend transport rejects failures and disconnects; obsolete socket cannot update a replacement', async () => {
  const previousSocket = globalThis.WebSocket, previousLocation = globalThis.location;
  const sockets = [];
  class FakeSocket {
    static OPEN = 1; readyState = 1; sent = [];
    constructor() { sockets.push(this); }
    close() { this.readyState = 3; }
    send(raw) { this.sent.push(JSON.parse(raw)); }
    receive(data) { this.onmessage({data:JSON.stringify(data)}); }
  }
  globalThis.WebSocket = FakeSocket; globalThis.location = {protocol:'http:',host:'localhost'};
  const connection = new Connection();
  try {
    connection.connect(); const old = sockets[0]; old.receive({type:'ready'});
    const failed = connection.request('book',{eventId:'E001',seat:'A1',name:'Ada'});
    old.receive({type:'response',id:old.sent[0].id,result:{kind:'error',message:'Database error'}});
    await assert.rejects(failed,/Database error/);
    const pending = connection.request('events');
    const rejected = assert.rejects(pending,/Session ended/);
    connection.connect(); await rejected;
    const fresh = sockets[1]; fresh.receive({type:'ready'}); fresh.receive({type:'stats',count:2});
    old.receive({type:'stats',count:99}); old.onclose();
    assert.equal(connection.snapshot().count,2); assert.equal(connection.snapshot().online,true);
    const request = connection.request('events');
    fresh.receive({type:'response',id:fresh.sent[0].id,result:{kind:'events',events:[]}});
    assert.equal((await request).kind,'events');
  } finally { connection.disconnect(); globalThis.WebSocket = previousSocket; globalThis.location = previousLocation; }
});
