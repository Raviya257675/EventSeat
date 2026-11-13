package com.eventseat.server;

import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;

/** One outstanding challenge per connection; state is guarded by this monitor. */
public final class HeartbeatManager implements AutoCloseable {
    private static final class Session {
        String pending;
        long sent, next, lastSuccess, latency = -1;
        Session(long now) { next = now; }
    }
    private final Map<ClientHandler, Session> sessions = new LinkedHashMap<>();
    private final ScheduledExecutorService scheduler;
    private final long interval, timeout;
    private long sequence;
    private final String epoch = UUID.randomUUID().toString();
    private boolean closed;
    public HeartbeatManager() {
        this(Duration.ofMillis(Long.getLong("eventseat.heartbeat.intervalMs", 5000L)),
             Duration.ofMillis(Long.getLong("eventseat.heartbeat.timeoutMs", 15000L)));
    }
    public HeartbeatManager(Duration interval, Duration timeout) {
        this.interval = interval.toNanos();
        this.timeout = timeout.toNanos();
        if (this.interval <= 0 || this.timeout <= 0) throw new IllegalArgumentException("Positive heartbeat durations required");
        scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "eventseat-heartbeat"); t.setDaemon(true); return t;
        });
        long tick = Math.max(1, Math.min(100_000_000L, Math.min(this.interval, this.timeout)));
        scheduler.scheduleWithFixedDelay(this::tick, tick, tick, TimeUnit.NANOSECONDS);
    }
    synchronized void register(ClientHandler client) {
        if (closed) { client.disconnect(); return; }
        sessions.put(client, new Session(System.nanoTime()));
        broadcastCount();
    }
    synchronized void unregister(ClientHandler client) {
        if (sessions.remove(client) != null) broadcastCount();
    }
    private void broadcastCount() {
        String message = "CLIENT_STATS:" + sessions.size();
        sessions.keySet().forEach(c -> c.enqueueMonitoring(message));
    }
    public synchronized int onlineCount() { return sessions.size(); }
    synchronized void pong(ClientHandler client, String id) {
        Session s = sessions.get(client);
        long now = System.nanoTime();
        if (s == null || s.pending == null || !s.pending.equals(id) || now - s.sent >= timeout) return;
        s.latency = TimeUnit.NANOSECONDS.toMillis(now - s.sent);
        s.lastSuccess = now;
        s.pending = null;
        s.next = now + interval;
        client.enqueueMonitoring("LATENCY:" + s.latency);
    }
    private synchronized void tick() {
        if (closed) return;
        long now = System.nanoTime();
        boolean changed = false;
        for (var it = sessions.entrySet().iterator(); it.hasNext();) {
            var entry = it.next();
            ClientHandler c = entry.getKey(); Session s = entry.getValue();
            if (c.isConnectionClosed() || (s.pending != null && now - s.sent >= timeout)) {
                it.remove(); changed = true;
                c.disconnect(); // No EventManager lock; handler finally releases owned holds.
            } else if (s.pending == null && now - s.next >= 0) {
                s.pending = epoch + "-" + (++sequence); s.sent = now;
                c.enqueueMonitoring("PING:" + s.pending);
            }
        }
        if (changed) broadcastCount();
    }
    @Override public void close() {
        synchronized (this) {
            if (closed) return;
            closed = true; scheduler.shutdownNow();
            sessions.keySet().forEach(ClientHandler::disconnect); sessions.clear();
        }
        try { scheduler.awaitTermination(2, TimeUnit.SECONDS); }
        catch (InterruptedException e) { Thread.currentThread().interrupt(); }
    }
    public boolean isTerminated() { return scheduler.isTerminated(); }
}
