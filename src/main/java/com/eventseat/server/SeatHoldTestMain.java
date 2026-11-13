package com.eventseat.server;

import com.eventseat.model.Event;
import com.eventseat.model.Seat;
import java.io.*;
import java.net.*;
import java.util.*;

/** Actual TCP tests. --embedded uses isolated events, never database bookings. */
public final class SeatHoldTestMain {
    private static final class Client extends TcpTestClient {
        Client(String host, int port) throws IOException { super(host, port); }
    }

    private static void check(boolean condition, String name) {
        if (!condition) throw new AssertionError(name);
        System.out.println("PASS: " + name);
    }

    public static void main(String[] args) throws Exception {
        boolean embedded = args.length > 0 && args[0].equals("--embedded");
        EventManager fixture = null;
        ServerSocket listener = null;
        String host = args.length > 0 && !embedded ? args[0] : "localhost";
        int port = args.length > 1 ? Integer.parseInt(args[1]) : 5000;
        if (embedded) {
            Map<String, Event> events = new LinkedHashMap<>();
            for (String id : List.of("E001", "E002")) {
                Event event = new Event(id, "Test", "Test", "Test");
                event.addSeat(new Seat("A1"));
                event.addSeat(new Seat("A2"));
                events.put(id, event);
            }
            fixture = new EventManager(events);
            listener = new ServerSocket(0, 50, InetAddress.getLoopbackAddress());
            port = listener.getLocalPort();
            final ServerSocket server = listener;
            final EventManager manager = fixture;
            Thread acceptor = new Thread(() -> {
                try {
                    while (!server.isClosed()) new ClientHandler(server.accept(), manager).start();
                } catch (IOException ignored) { }
            }, "test-acceptor");
            acceptor.setDaemon(true);
            acceptor.start();
        }
        try (Client a = new Client(host, port); Client b = new Client(host, port);
             Client otherEvent = new Client(host, port)) {
            String first = a.request("GET_SEATS:E001");
            String second = otherEvent.request("GET_SEATS:E002");
            if (!first.startsWith("SEATS:") || !second.startsWith("SEATS:"))
                throw new AssertionError("Prerequisite: E001 and E002 must exist");
            String seat = Arrays.stream(first.split(":", 3)[2].split(","))
                    .filter(row -> row.endsWith("=AVAILABLE") && second.contains(row + ","))
                    .map(row -> row.split("=")[0]).findFirst()
                    .orElseThrow(() -> new AssertionError("Need a same-named available seat in E001 and E002"));
            String key = "E001:" + seat;
            b.request("GET_SEATS:E001");
            check(a.request("HOLD:" + key).startsWith("HOLD_SUCCESS:"), "A acquires hold");
            b.update("SEAT_UPDATE:" + key + ":HELD", 5000);
            check(b.request("GET_SEATS:E001").contains(seat + "=HELD"), "Snapshot and broadcast show HELD");
            check(b.request("HOLD:" + key).equals("HOLD_DENIED:" + key + ":HELD"), "B cannot hold A's seat");
            check(b.request("BOOK:" + key + ":HoldTest").equals("BOOKING_FAILED:HELD"), "B cannot book A's seat");
            check(b.request("RELEASE:" + key).startsWith("RELEASE_DENIED:"), "B cannot release A's seat");
            check(a.request("RELEASE:" + key).startsWith("RELEASE_SUCCESS:"), "A releases hold");
            check(b.request("HOLD:" + key).startsWith("HOLD_SUCCESS:"), "B acquires released seat");
            check(otherEvent.request("HOLD:E002:" + seat).startsWith("HOLD_SUCCESS:"), "Same seat in E002 is independent");
            check(otherEvent.updates.stream().noneMatch(u -> u.startsWith("SEAT_UPDATE:E001:")), "Broadcasts are event-specific");
            otherEvent.request("RELEASE:E002:" + seat);
            // Drain earlier AVAILABLE updates before waiting for the actual expiration.
            a.request("GET_SEATS:E001");
            a.updates.clear();
            System.out.println("Waiting for the real 60-second expiration...");
            a.update("SEAT_UPDATE:" + key + ":AVAILABLE", 65000);
            check(a.request("GET_SEATS:E001").contains(seat + "=AVAILABLE"), "Expired hold becomes available");
            try (Client departing = new Client(host, port)) {
                check(departing.request("HOLD:" + key).startsWith("HOLD_SUCCESS:"), "Departing client holds seat");
            }
            a.update("SEAT_UPDATE:" + key + ":AVAILABLE", 5000);
            check(a.request("HOLD:" + key).startsWith("HOLD_SUCCESS:"), "Disconnect releases hold");
            check(a.request("LEAVE_SEATS").equals("LEAVE_SUCCESS"), "Leaving releases hold");
            check(b.request("GET_SEATS:E001").contains(seat + "=AVAILABLE"), "Seat available after leaving");
            check(b.request("HOLD:E001:" + seat + ":fake-owner").equals("INVALID_HOLD_REQUEST"), "Malformed owner input rejected");
            try (Client exiting = new Client(host, port)) {
                check(exiting.request("HOLD:" + key).startsWith("HOLD_SUCCESS:"), "EXIT client holds seat");
                check(exiting.request("EXIT").equals("Goodbye!"), "EXIT preserves farewell reply");
            }
            check(b.request("GET_SEATS:E001").contains(seat + "=AVAILABLE"), "EXIT releases hold");
            System.out.println("ALL SEAT HOLD TCP TESTS PASSED (no bookings created)");
        } finally {
            if (listener != null) listener.close();
            if (fixture != null) fixture.close();
        }
    }
}
