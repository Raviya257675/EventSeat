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
        Platform.startup(() -> { });
        Platform.runLater(() -> {
            try { uiChecks(); } catch (Throwable error) { failure.set(error); }
            finally { finished.countDown(); }
        });
        try {
            if (!finished.await(20, TimeUnit.SECONDS)) throw new AssertionError("JavaFX checks timed out");
            if (failure.get() != null) throw new AssertionError("JavaFX checks failed", failure.get());
            System.out.println("ALL UI REGRESSION CHECKS PASSED");
        } finally { Platform.exit(); }
    }
}
