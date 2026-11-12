package com.eventseat.server;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

/** State and timer callbacks use the shared EventManager monitor. */
public final class SeatHoldManager implements AutoCloseable {
    public static final long HOLD_MILLIS = 60_000;
    private record Key(String event, String seat) {
        Key { seat = seat.toUpperCase(java.util.Locale.ROOT); }
    }
    public record Hold(String eventId, String seatNumber, Object owner, long expiresAt) {}
    private final Map<Key, Hold> holds = new HashMap<>();
    private final Map<Key, ScheduledFuture<?>> timers = new HashMap<>();
    private final EventManager manager;
    private final ScheduledThreadPoolExecutor scheduler = new ScheduledThreadPoolExecutor(1, task -> {
        Thread thread = new Thread(task, "seat-hold-expiry");
        thread.setDaemon(true);
        return thread;
    });

    SeatHoldManager(EventManager manager) {
        this.manager = manager;
        scheduler.setRemoveOnCancelPolicy(true);
    }

    Hold get(String event, String seat) {
        synchronized (manager) {
            Key key = new Key(event, seat);
            Hold hold = holds.get(key);
            if (hold != null && hold.expiresAt() <= System.currentTimeMillis()) {
                expire(key, hold);
                return null;
            }
            return hold;
        }
    }

    Hold create(String event, String seat, Object owner) {
        synchronized (manager) {
            Key key = new Key(event, seat);
            Hold hold = new Hold(event, seat, owner, System.currentTimeMillis() + HOLD_MILLIS);
            holds.put(key, hold);
            timers.put(key, scheduler.schedule(() -> expire(key, hold), HOLD_MILLIS, TimeUnit.MILLISECONDS));
            return hold;
        }
    }

    private void expire(Key key, Hold expected) {
        synchronized (manager) {
            // Reference identity prevents old timers releasing a replacement hold.
            if (holds.get(key) == expected) {
                remove(key.event(), key.seat());
                manager.broadcast(expected.eventId(), expected.seatNumber(), "AVAILABLE");
            }
        }
    }

    void remove(String event, String seat) {
        synchronized (manager) {
            Key key = new Key(event, seat);
            holds.remove(key);
            ScheduledFuture<?> timer = timers.remove(key);
            if (timer != null) timer.cancel(false);
        }
    }

    void releaseOwner(Object owner) {
        synchronized (manager) {
            for (Hold hold : holds.values().toArray(Hold[]::new)) {
                if (hold.owner() == owner) expire(new Key(hold.eventId(), hold.seatNumber()), hold);
            }
        }
    }

    @Override public void close() {
        synchronized (manager) {
            scheduler.shutdownNow();
            timers.clear();
            holds.clear();
        }
    }
}
