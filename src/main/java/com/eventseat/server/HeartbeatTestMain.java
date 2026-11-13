package com.eventseat.server;

import com.eventseat.client.ServerConnection;
import com.eventseat.model.Event;
import com.eventseat.model.Seat;
import java.io.*;
import java.net.*;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;

/** Real loopback sockets; no database access. */
public final class HeartbeatTestMain {
    private static void check(boolean value, String name) {
        if (!value) throw new AssertionError(name);
        System.out.println("PASS: " + name);
    }
    private static void slowOperationRegression() throws Exception {
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        Event event = new Event("E001", "Slow operation", "Test", "Test");
        event.addSeat(new Seat("A1")); event.addSeat(new Seat("A2")); event.addSeat(new Seat("A3"));
        HeartbeatManager heartbeat = new HeartbeatManager(Duration.ofMillis(150), Duration.ofMillis(1200));
        EventManager manager = new EventManager(Map.of("E001", event), heartbeat) {
            @Override public synchronized String getEventDetails(String id) {
                entered.countDown();
                try {
                    if (!release.await(10, TimeUnit.SECONDS)) throw new AssertionError("Slow-operation gate timed out");
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new AssertionError("Healthy command worker was interrupted", e);
                }
                return super.getEventDetails(id);
            }
        };
        List<ClientHandler> handlers = new CopyOnWriteArrayList<>();
        try (ServerSocket server = new ServerSocket(0, 50, InetAddress.getLoopbackAddress())) {
            Thread acceptor = new Thread(() -> {
                try {
                    while (!server.isClosed()) {
                        ClientHandler h = new ClientHandler(server.accept(), manager);
                        handlers.add(h); h.start();
                    }
                } catch (IOException ignored) { }
            }, "slow-operation-acceptor");
            acceptor.start();
            try (TcpTestClient owner = new TcpTestClient("localhost", server.getLocalPort());
                 TcpTestClient waiting = new TcpTestClient("localhost", server.getLocalPort());
                 TcpTestClient dead = new TcpTestClient("localhost", server.getLocalPort())) {
                owner.request("GET_SEATS:E001"); waiting.request("GET_SEATS:E001");
                String owned = owner.request("HOLD:E001:A1");
                String waitingHold = waiting.request("HOLD:E001:A2");
                check(owned.startsWith("HOLD_SUCCESS:") && waitingHold.startsWith("HOLD_SUCCESS:"), "Slow-operation peers own holds");
                owner.writer.println("GET_EVENT");
                check(entered.await(3, TimeUnit.SECONDS), "Ordinary command entered blocked operation");
                long blockedSince = System.nanoTime();
                owner.writer.println("UNKNOWN_FIFO_PROBE");
                owner.writer.println("GET_SEATS:E001");
                waiting.writer.println("GET_SEATS:E001"); // waits for the same EventManager lock
                dead.writer.println("HOLD:E001:A3"); // Must not run after timeout while waiting for the lock.
                dead.pauseResponses();
                dead.await("PING:", 3000);
                dead.await("EOF", 4000); // Real timeout is our deterministic clock, not a sleep.
                check(System.nanoTime() - blockedSince >= TimeUnit.MILLISECONDS.toNanos(1200), "Operation blocked beyond heartbeat timeout");
                try (TcpTestClient overloaded = new TcpTestClient("localhost", server.getLocalPort())) {
                    for (int i = 0; i < 600; i++) overloaded.writer.println("HOLD:E001:A3");
                    overloaded.await("EOF", 3000);
                    check(true, "Full bounded command queue disconnects overloaded peer without blocking reader");
                }
                owner.messages.clear(); waiting.messages.clear();
                owner.await("LATENCY:", 3000); waiting.await("LATENCY:", 3000);
                check(heartbeat.onlineCount() == 2, "Busy worker and lock-waiting worker both keep answering heartbeats; silent peer times out");
                release.countDown();
                check(owner.response().equals(event.toString()), "Slow operation returns normally");
                check(owner.response().equals("UNKNOWN_COMMAND"), "Ordinary commands preserve FIFO order");
                String snapshot = owner.response();
                check(snapshot.contains("A1=HELD"), "Owner hold survives slow operation");
                check(snapshot.contains("A3=AVAILABLE"), "Disconnected queued hold commands are discarded");
                check(waiting.response().contains("A2=HELD"), "Lock-waiting peer hold survives");
                check(owner.request("HOLD:E001:A1").equals(owned)
                        && waiting.request("HOLD:E001:A2").equals(waitingHold), "Heartbeat traffic does not renew hold expiry");
            } finally {
                release.countDown();
                server.close(); acceptor.join(2000);
                manager.close();
                for (ClientHandler h : handlers) { h.join(3000); check(!h.isAlive(), "Slow-operation handler stopped"); }
            }
        } finally { release.countDown(); manager.close(); }
        check(heartbeat.isTerminated(), "Slow-operation scheduler stopped");
    }

    public static void main(String[] args) throws Exception {
        slowOperationRegression();
        HeartbeatManager heartbeat = new HeartbeatManager(Duration.ofMillis(150), Duration.ofMillis(1200));
        Event event = new Event("E001", "Test", "Test", "Test");
        event.addSeat(new Seat("A1")); event.addSeat(new Seat("A2"));
        List<ClientHandler> handlers = new CopyOnWriteArrayList<>();
        EventManager manager = new EventManager(Map.of("E001", event), heartbeat);
        try (ServerSocket server = new ServerSocket(0, 50, InetAddress.getLoopbackAddress())) {
            Thread acceptor = new Thread(() -> {
                try {
                    while (!server.isClosed()) {
                        ClientHandler h = new ClientHandler(server.accept(), manager);
                        handlers.add(h); h.start();
                    }
                } catch (IOException ignored) { }
            }, "heartbeat-test-acceptor");
            acceptor.start();
            int port = server.getLocalPort();
            try (TcpTestClient a = new TcpTestClient("localhost", port)) {
                check(a.await("CLIENT_STATS:1", 3000).equals("CLIENT_STATS:1"), "Initial count is one");
                String old = a.await("PING:", 3000).substring(5);
                check(Long.parseLong(a.await("LATENCY:", 3000).substring(8)) >= 0, "Matching PONG yields nonnegative RTT");
                try (TcpTestClient b = new TcpTestClient("localhost", port)) {
                    check(a.await("CLIENT_STATS:2", 3000).equals("CLIENT_STATS:2"), "Count increases to two");
                    b.await("CLIENT_STATS:2", 3000);
                    a.request("GET_SEATS:E001");
                    b.request("GET_SEATS:E001");
                    check(b.request("HOLD:E001:A1").startsWith("HOLD_SUCCESS:"), "Peer acquires temporary hold");
                    a.update("SEAT_UPDATE:E001:A1:HELD", 3000);
                    check(a.request("BOOK:E001:A1:Test").equals("BOOKING_FAILED:HELD"), "Booking ownership protocol preserved");
                    b.pauseResponses();
                    String pending = b.await("PING:", 3000).substring(5);
                    b.writer.println("PONG:incorrect");
                    b.writer.println("PONG:" + old); // challenge from a different connection
                    b.writer.println("PONG:" + pending + " "); // exact echo required
                    b.writer.println("GET_EVENT"); // ordinary activity cannot replace a heartbeat
                    String line;
                    do {
                        line = b.await("", 3000);
                        check(!line.startsWith("LATENCY:"), "Invalid/cross-session PONG cannot satisfy challenge");
                    } while (!line.equals("EOF"));
                    check(a.await("CLIENT_STATS:1", 3000).equals("CLIENT_STATS:1"), "Timeout removes only unresponsive peer");
                    a.update("SEAT_UPDATE:E001:A1:AVAILABLE", 3000);
                    check(a.request("GET_SEATS:E001").contains("A1=AVAILABLE"), "Timeout releases hold through existing cleanup");
                    check(a.request("GET_EVENTS").startsWith("EVENTS:"), "Healthy peer remains usable");
                }
                // Replayed valid response must not satisfy the next challenge on the same socket.
                a.pauseResponses();
                String pending = a.await("PING:", 3000).substring(5);
                a.writer.println("PONG:" + pending);
                a.await("LATENCY:", 3000);
                a.await("PING:", 3000);
                a.writer.println("PONG:" + pending);
                String line;
                do {
                    line = a.await("", 3000);
                    check(!line.startsWith("LATENCY:"), "Stale PONG rejected");
                } while (!line.equals("EOF"));
            }
            check(heartbeat.onlineCount() == 0, "No timed-out sessions remain online");
            BlockingQueue<String> received = new LinkedBlockingQueue<>();
            ServerConnection realClient = new ServerConnection(received::add);
            try {
                realClient.connect("localhost", port);
                long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(4);
                boolean measured = false;
                while (System.nanoTime() < deadline && !measured) {
                    String message = received.poll(1, TimeUnit.SECONDS);
                    if (message != null) {
                        check(!message.startsWith("PING:"), "Client consumes PING internally");
                        measured = message.startsWith("LATENCY:");
                    }
                }
                check(measured, "Real ServerConnection automatically answers on original socket");
                try (TcpTestClient observer = new TcpTestClient("localhost", port)) {
                    observer.await("CLIENT_STATS:2", 3000);
                    realClient.close();
                    observer.await("CLIENT_STATS:1", 3000);
                    check(observer.request("EXIT").equals("Goodbye!"), "Disconnect count and EXIT farewell preserved");
                }
                realClient.connect("localhost", port);
                received.clear();
                realClient.send("GET_EVENTS");
                boolean reconnected = false;
                for (int i = 0; i < 10 && !reconnected; i++) {
                    String message = received.poll(1, TimeUnit.SECONDS);
                    reconnected = message != null && message.startsWith("LATENCY:");
                }
                check(reconnected, "Reconnected client has independent heartbeat lifecycle");
                manager.close();
                boolean disconnected = false;
                for (int i = 0; i < 5 && !disconnected; i++) {
                    String message = received.poll(1, TimeUnit.SECONDS);
                    disconnected = message != null && message.startsWith("CONNECTION_ERROR:");
                }
                check(disconnected && !realClient.isConnected(), "Manager shutdown closes active sockets");
            } finally { realClient.close(); }
            server.close(); acceptor.join(2000);
            check(!acceptor.isAlive(), "Acceptor stopped");
        } finally {
            manager.close();
            for (ClientHandler h : handlers) {
                h.join(3000);
                check(!h.isAlive(), "Client handler stopped");
            }
        }
        check(heartbeat.isTerminated() && heartbeat.onlineCount() == 0, "Heartbeat scheduler terminated and sessions cleared");
        for (Thread thread : Thread.getAllStackTraces().keySet()) {
            if (Set.of("eventseat-heartbeat", "eventseat-client-writer", "eventseat-command-worker", "eventseat-sender", "tcp-test-reader")
                    .contains(thread.getName())) {
                thread.join(2500);
                check(!thread.isAlive(), "Transport worker terminated: " + thread.getName());
            }
        }
        System.out.println("ALL HEARTBEAT TCP TESTS PASSED");
    }
}
