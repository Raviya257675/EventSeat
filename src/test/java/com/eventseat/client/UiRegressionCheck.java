package com.eventseat.client;

import javafx.application.Platform;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/** Standalone JavaFX regression checks; no server, database, or extra dependencies. */
public final class UiRegressionCheck {
    private static Field field(String name) throws Exception {
        Field field = EventSeatApp.class.getDeclaredField(name);
        field.setAccessible(true);
        return field;
    }

    private static void call(EventSeatApp app, String name, Class<?>[] types, Object... values) throws Exception {
        Method method = EventSeatApp.class.getDeclaredMethod(name, types);
        method.setAccessible(true);
        method.invoke(app, values);
    }

    private static void check(boolean condition, String description) {
        if (!condition) throw new AssertionError(description);
        System.out.println("PASS: " + description);
    }

    private static void owned(EventSeatApp app, long expiry) throws Exception {
        field("selectedSeat").set(app, "A1");
        field("holdOwned").set(app, true);
        field("holdExpiry").set(app, expiry);
        field("desiredHold").set(app, "E001:A1");
        ((Label) field("bookingStatusLabel").get(app)).setText("Seat held for you. Confirm before the timer expires.");
    }

    private static void cleared(EventSeatApp app, String expected) throws Exception {
        check(!(boolean) field("holdOwned").get(app) && field("selectedSeat").get(app) == null
                && ((Label) field("selectedSeatLabel").get(app)).getText().equals("Selected Seat: None")
                && ((Button) field("bookButton").get(app)).isDisabled()
                && ((Label) field("bookingStatusLabel").get(app)).getText().contains(expected), expected);
    }

    @SuppressWarnings("unchecked")
    private static void uiChecks() throws Exception {
        EventSeatApp networkApp = new EventSeatApp();
        final boolean[] connected = {true};
        field("serverConnection").set(networkApp, new ServerConnection(message -> { }) {
            @Override public boolean isConnected() { return connected[0]; }
        });
        call(networkApp, "createConnectionBar", new Class<?>[0]);
        Label network = (Label) field("networkStatusLabel").get(networkApp);
        check(network.getText().contains("Measuring...") && network.getText().contains("Waiting for heartbeat"),
                "No fake latency before first heartbeat");
        call(networkApp, "handleServerMessage", new Class<?>[]{String.class}, "CLIENT_STATS:2");
        call(networkApp, "handleServerMessage", new Class<?>[]{String.class}, "LATENCY:14");
        check(network.getText().contains("Online Clients: 2") && network.getText().contains("14 ms")
                && network.getText().contains("Monitoring"), "Network count and RTT update on JavaFX thread");
        call(networkApp, "createConnectionBar", new Class<?>[0]);
        network = (Label) field("networkStatusLabel").get(networkApp);
        check(network.getText().contains("14 ms"), "Monitoring survives screen recreation");
        connected[0] = false;
        call(networkApp, "updateConnectionBar", new Class<?>[0]);
        check(network.getText().contains("Connection lost") && !network.getText().contains("14 ms"),
                "Disconnect clears displayed network values");
        EventSeatApp app = new EventSeatApp();
        field("selectedSeatLabel").set(app, new Label());
        field("bookingStatusLabel").set(app, new Label());
        field("bookButton").set(app, new Button());
        ((Map<String, Button>) field("seatButtons").get(app)).put("A1", new Button("A1"));

        owned(app, System.currentTimeMillis() - 1);
        call(app, "tickHold", new Class<?>[0]);
        cleared(app, "Hold expired");

        owned(app, System.currentTimeMillis() + 60000);
        call(app, "updateSeat", new Class<?>[]{String.class, String.class}, "A1", "AVAILABLE");
        cleared(app, "Hold released");
        call(app, "updateSeat", new Class<?>[]{String.class, String.class}, "A1", "HELD");
        cleared(app, "Hold released");
        Button seat = ((Map<String, Button>) field("seatButtons").get(app)).get("A1");
        check(seat.getStyle().contains("gold") && seat.isDisabled(), "Other client's hold stays yellow and disabled");

        owned(app, System.currentTimeMillis() - 1);
        call(app, "updateSeat", new Class<?>[]{String.class, String.class}, "A1", "AVAILABLE");
        cleared(app, "Hold expired");

        owned(app, System.currentTimeMillis() + 60000);
        call(app, "updateSeat", new Class<?>[]{String.class, String.class}, "A1", "BOOKED");
        cleared(app, "Seat is booked");
        check(seat.getStyle().contains("lightcoral"), "Booked seat stays red");

        owned(app, System.currentTimeMillis() + 60000);
        call(app, "onConnectionLost", new Class<?>[0]);
        cleared(app, "Server disconnected");

        owned(app, System.currentTimeMillis() + 60000);
        call(app, "leaveSeats", new Class<?>[0]);
        cleared(app, "");
        check(((Label) field("bookingStatusLabel").get(app)).getText().isEmpty(), "Navigation clears hold notice");

        // Exercise the real message handler, including a confirmation delayed past expiry.
        field("currentScreen").set(app, "SEATS");
        for (long expiry : new long[]{System.currentTimeMillis() + 60000, System.currentTimeMillis() - 1}) {
            field("selectedSeat").set(app, "A1");
            field("desiredHold").set(app, "E001:A1");
            ((java.util.ArrayDeque<String>) field("holdRequests").get(app)).add("E001:A1");
            call(app, "handleServerMessage", new Class<?>[]{String.class}, "HOLD_SUCCESS:E001:A1:" + expiry);
            if (expiry > System.currentTimeMillis()) {
                check((boolean) field("holdOwned").get(app)
                        && ((Label) field("bookingStatusLabel").get(app)).getText().startsWith("Seat held for you"),
                        "Successful acquisition shows owned notice");
            } else cleared(app, "Hold expired");
        }
    }

    @FunctionalInterface private interface FxAction { void run() throws Exception; }

    private static void onFx(FxAction action) throws Exception {
        CountDownLatch done = new CountDownLatch(1);
        AtomicReference<Throwable> failure = new AtomicReference<>();
        Platform.runLater(() -> {
            try { action.run(); } catch (Throwable t) { failure.set(t); }
            finally { done.countDown(); }
        });
        if (!done.await(5, TimeUnit.SECONDS)) throw new AssertionError("FX action timed out");
        if (failure.get() != null) throw new AssertionError("FX action failed", failure.get());
    }

    /** Hold the real FX queue until the transport has received a response AND EOF. */
    private static void responseThenEof(String response, boolean replace) throws Exception {
        EventSeatApp app = new EventSeatApp();
        CountDownLatch eof = new CountDownLatch(1);
        CountDownLatch unblockFx = new CountDownLatch(1);
        CountDownLatch fxBlocked = new CountDownLatch(1);
        CountDownLatch releaseReplacement = new CountDownLatch(1);
        AtomicReference<Throwable> serverFailure = new AtomicReference<>();
        java.util.concurrent.atomic.AtomicInteger confirmations = new java.util.concurrent.atomic.AtomicInteger();
        java.util.concurrent.atomic.AtomicInteger cancellations = new java.util.concurrent.atomic.AtomicInteger();
        java.util.List<String> transportOrder = new java.util.concurrent.CopyOnWriteArrayList<>();
        ServerConnection connection = new ServerConnection(message -> {
            transportOrder.add(message);
            app.enqueueServerMessage(message);
            if (message.startsWith("CONNECTION_ERROR:")) eof.countDown();
        });
        javafx.collections.ListChangeListener<javafx.stage.Window> dialogs = change -> {
            while (change.next()) for (javafx.stage.Window window : change.getAddedSubList()) {
                if (window instanceof javafx.stage.Stage stage
                        && stage.getTitle().equals("EventSeat - Booking Confirmation")) {
                    confirmations.incrementAndGet();
                    Platform.runLater(window::hide); // Close the real modal confirmation without user input.
                }
            }
        };
        onFx(() -> {
            field("serverConnection").set(app, connection);
            field("currentScreen").set(app, "LOOKUP");
            field("bookButton").set(app, new Button());
            field("cancelButton").set(app, new Button());
            javafx.scene.control.TextArea result = new javafx.scene.control.TextArea("Replacement baseline");
            result.textProperty().addListener((obs, before, after) -> {
                if (after.startsWith("BOOKING CANCELLED SUCCESSFULLY!")
                        && !before.startsWith("BOOKING CANCELLED SUCCESSFULLY!")) cancellations.incrementAndGet();
            });
            field("bookingLookupResultArea").set(app, result);
            call(app, "createConnectionBar", new Class<?>[0]);
            javafx.stage.Window.getWindows().addListener(dialogs);
        });
        try (java.net.ServerSocket server = new java.net.ServerSocket(0, 50, java.net.InetAddress.getLoopbackAddress())) {
            Thread peer = new Thread(() -> {
                try {
                    try (java.net.Socket socket = server.accept()) {
                        java.io.PrintWriter writer = new java.io.PrintWriter(socket.getOutputStream(), true);
                        if (!response.isEmpty()) writer.println(response);
                    }
                    if (replace) try (java.net.Socket replacement = server.accept()) {
                        releaseReplacement.await(10, TimeUnit.SECONDS);
                    }
                } catch (Throwable t) { serverFailure.set(t); }
            }, "ui-eof-test-server");
            peer.start();
            try {
                Platform.runLater(() -> {
                    fxBlocked.countDown();
                    try { unblockFx.await(10, TimeUnit.SECONDS); }
                    catch (InterruptedException e) { Thread.currentThread().interrupt(); }
                });
                check(fxBlocked.await(3, TimeUnit.SECONDS), "FX dispatch intentionally delayed");
                connection.connect("localhost", server.getLocalPort());
                long original = connection.connectionGeneration();
                check(eof.await(3, TimeUnit.SECONDS), "Transport received EOF before UI dispatch");
                check(connection.connectionGeneration() == original, "EOF preserves connection identity");
                if (!response.isEmpty()) check(transportOrder.size() == 2 && transportOrder.get(0).equals(response)
                        && transportOrder.get(1).startsWith("CONNECTION_ERROR:"), "Response precedes one disconnect notification");
                if (replace) {
                    connection.connect("localhost", server.getLocalPort());
                    check(connection.connectionGeneration() != original, "Replacement gets new identity");
                }
                unblockFx.countDown();
                onFx(() -> {
                    String result = ((javafx.scene.control.TextArea) field("bookingLookupResultArea").get(app)).getText();
                    if (replace) {
                        check(confirmations.get() == 0 && cancellations.get() == 0
                                && result.equals("Replacement baseline") && connection.isConnected(),
                                "Old response and EOF cannot alter replacement connection UI");
                    } else {
                        check(confirmations.get() == (response.startsWith("BOOKING_SUCCESS:") ? 1 : 0),
                                "Already-received booking confirmation appears exactly once; EOF alone creates none");
                        check(cancellations.get() == (response.startsWith("CANCEL_SUCCESS:") ? 1 : 0),
                                "Already-received cancellation appears exactly once");
                        if (response.startsWith("CANCEL_SUCCESS:")) check(result.contains("BOOKING CANCELLED SUCCESSFULLY!")
                                && result.contains("Server disconnected"), "Cancellation result remains visible after disconnect");
                        check(((Label) field("connectionStatusLabel").get(app)).getText().equals("Server: Disconnected"),
                                "EOF updates disconnected UI on FX thread");
                    }
                });
            } finally {
                unblockFx.countDown();
                connection.close(); releaseReplacement.countDown(); server.close(); peer.join(3000);
                onFx(() -> javafx.stage.Window.getWindows().removeListener(dialogs));
            }
            check(!peer.isAlive() && serverFailure.get() == null, "EOF test peer shuts down cleanly");
        }
    }

    public static void main(String[] args) throws Exception {
        var result = EventSeatApp.parseBookingSuccess("BOOKING_SUCCESS:E001:A1:1:2026-11-12 20:16:16");
        check(result.bookingId().equals("1") && result.bookingTime().equals("2026-11-12 20:16:16"),
                "Booking ID and full timestamp are separate");
        check(EventSeatApp.parseBookingSuccess("BOOKING_SUCCESS:E001:A1:23").bookingTime().isEmpty(),
                "Legacy confirmation without timestamp supported");
        check(EventSeatApp.parseBookingSuccess("BOOKING_SUCCESS:E001:A1:2:2026-11-12 20:16:16.123").bookingTime()
                .endsWith("16.123"), "Fractional timestamp preserved");
        for (String malformed : new String[]{"BOOKING_SUCCESS:E001:A1", "BOOKING_SUCCESS:E001:A1:abc:time"}) {
            try {
                EventSeatApp.parseBookingSuccess(malformed);
                throw new AssertionError("Malformed confirmation accepted");
            } catch (IllegalArgumentException expected) { }
        }
        CountDownLatch finished = new CountDownLatch(1);
        AtomicReference<Throwable> failure = new AtomicReference<>();
        Platform.startup(() -> Platform.setImplicitExit(false));
        Platform.runLater(() -> {
            try { uiChecks(); } catch (Throwable error) { failure.set(error); }
            finally { finished.countDown(); }
        });
        try {
            if (!finished.await(20, TimeUnit.SECONDS)) throw new AssertionError("JavaFX checks timed out");
            if (failure.get() != null) throw new AssertionError("JavaFX checks failed", failure.get());
            for (String response : new String[]{"BOOKING_SUCCESS:E001:A1:42:2026-11-13 10:00:00", "CANCEL_SUCCESS:42:E001:A1"}) {
                responseThenEof(response, false);
                responseThenEof(response, true);
            }
            responseThenEof("", false);
            System.out.println("ALL UI REGRESSION CHECKS PASSED");
        } finally { Platform.exit(); }
    }
}
