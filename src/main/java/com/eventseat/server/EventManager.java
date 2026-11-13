package com.eventseat.server;

import com.eventseat.model.Event;
import com.eventseat.model.Seat;
import com.eventseat.repository.BookingCancellationRepository;
import com.eventseat.repository.BookingRepository;
import com.eventseat.repository.EventRepository;

import java.sql.SQLException;
import java.util.LinkedHashMap;
import java.util.Map;

public class EventManager implements AutoCloseable {
    private final SeatHoldManager holds = new SeatHoldManager(this);

    private final Map<String, Event> events;
    private final BookingRepository bookingRepository = new BookingRepository();
    private final BookingCancellationRepository cancellationRepository =
            new BookingCancellationRepository();

    public EventManager() {
        try {
            events = new LinkedHashMap<>(new EventRepository().loadAllEvents());
            if (events.isEmpty()) {
                throw new IllegalStateException("No events found in eventseat_db.");
            }
            System.out.println("Loaded " + events.size() + " events from MySQL.");
            for (Event event : events.values()) {
                System.out.println(event.getEventId() + " - " + event.getName() +
                        " (" + event.getSeats().size() + " seats)");
            }
        } catch (SQLException e) {
            throw new IllegalStateException("Failed to load events from MySQL.", e);
        }
    }

    // In-memory event fixture for the socket regression runner; production uses MySQL above.
    EventManager(Map<String, Event> testEvents) {
        events = new LinkedHashMap<>(testEvents);
    }

    public synchronized boolean hasEvent(String eventId) {
        return events.containsKey(eventId);
    }

    public synchronized String getEvents() {
        // Demo protocol: names/venues must not contain '|' or ';'.
        StringBuilder reply = new StringBuilder("EVENTS:");
        for (Event event : events.values()) {
            reply.append(event.getEventId()).append("|")
                    .append(event.getName()).append("|")
                    .append(event.getVenue()).append("|")
                    .append(event.getDate()).append(";");
        }
        return reply.toString();
    }

    public synchronized String getSeatStatus(String eventId) {
        Event event = events.get(eventId);
        if (event == null) return "EVENT_NOT_FOUND";

        StringBuilder reply = new StringBuilder("SEATS:").append(eventId).append(":");
        for (Seat seat : event.getSeats()) {
            reply.append(seat.getSeatNumber()).append("=")
                    .append(seat.isBooked() ? "BOOKED" : holds.get(eventId, seat.getSeatNumber()) != null ? "HELD" : "AVAILABLE").append(",");
        }
        return reply.toString();
    }

    public synchronized String getEventDetails(String eventId) {
        Event event = events.get(eventId);
        return event == null ? "EVENT_NOT_FOUND" : event.toString();
    }

    public synchronized String bookSeat(
            String eventId, String seatNumber, String customerName) {

        return bookSeat(eventId, seatNumber, customerName, null);
    }

    public synchronized String bookSeat(String eventId, String seatNumber, String customerName, Object owner) {
        Event event = events.get(eventId);
        if (event == null) return "EVENT_NOT_FOUND";

        Seat seat = event.findSeat(seatNumber);
        if (seat == null) return "SEAT_NOT_FOUND";
        if (customerName == null || customerName.isBlank() ||
                customerName.length() > 100 || customerName.contains(":")) {
            return "INVALID_CUSTOMER_NAME";
        }
        SeatHoldManager.Hold hold = holds.get(eventId, seatNumber);
        if (hold != null && hold.owner() != owner) return "BOOKING_FAILED:HELD";
        if (seat.isBooked()) return "ALREADY_BOOKED:" + seatNumber;

        try {
            String reply = bookingRepository.bookSeat(
                    eventId, seatNumber, customerName.trim());

            if (reply.startsWith("BOOKING_SUCCESS:") ||
                    reply.startsWith("ALREADY_BOOKED:")) {
                seat.setBooked(true);
                holds.remove(eventId, seatNumber);
            }
            return reply;
        } catch (SQLException e) {
            System.err.println("Database error while booking seat.");
            return "DATABASE_ERROR";
        }
    }

    public synchronized String cancelBooking(int bookingId, String customerName) {
        try {
            String reply = cancellationRepository.cancelBooking(bookingId, customerName);
            if (reply.startsWith("CANCEL_SUCCESS:")) {
                String[] parts = reply.split(":", 4);
                if (parts.length == 4) {
                    Event event = events.get(parts[2]);
                    if (event != null) {
                        Seat seat = event.findSeat(parts[3]);
                        if (seat != null) seat.setBooked(false);
                    }
                }
            }
            return reply;
        } catch (SQLException e) {
            System.err.println("Database error while cancelling booking.");
            return "DATABASE_ERROR";
        }
    }

    public synchronized String holdSeat(String eventId, String seatNumber, Object owner) {
        Event event = events.get(eventId);
        if (event == null) return "HOLD_DENIED:" + eventId + ":" + seatNumber + ":EVENT_NOT_FOUND";
        Seat seat = event.findSeat(seatNumber);
        if (seat == null) return "HOLD_DENIED:" + eventId + ":" + seatNumber + ":SEAT_NOT_FOUND";
        seatNumber = seat.getSeatNumber();
        String prefix = eventId + ":" + seatNumber;
        if (seat.isBooked()) return "HOLD_DENIED:" + prefix + ":BOOKED";
        SeatHoldManager.Hold previous = holds.get(eventId, seatNumber);
        if (previous != null && previous.owner() != owner) return "HOLD_DENIED:" + prefix + ":HELD";
        if (previous == null) {
            holds.releaseOwner(owner);
            previous = holds.create(eventId, seatNumber, owner);
            broadcast(eventId, seatNumber, "HELD");
        }
        return "HOLD_SUCCESS:" + prefix + ":" + previous.expiresAt();
    }

    public synchronized String releaseSeat(String eventId, String seatNumber, Object owner) {
        Event event = events.get(eventId);
        if (event == null) return "EVENT_NOT_FOUND";
        Seat seat = event.findSeat(seatNumber);
        if (seat == null) return "SEAT_NOT_FOUND";
        seatNumber = seat.getSeatNumber();
        SeatHoldManager.Hold hold = holds.get(eventId, seatNumber);
        if (hold != null && hold.owner() != owner) return "RELEASE_DENIED:" + eventId + ":" + seatNumber;
        if (hold != null) {
            holds.remove(eventId, seatNumber);
            broadcast(eventId, seatNumber, "AVAILABLE");
        }
        return "RELEASE_SUCCESS:" + eventId + ":" + seatNumber;
    }

    public synchronized void releaseOwner(Object owner) { holds.releaseOwner(owner); }

    void broadcast(String event, String seat, String status) {
        ClientHandler.broadcastToEvent(event, "SEAT_UPDATE:" + event + ":" + seat + ":" + status);
    }

    @Override public synchronized void close() { holds.close(); }
}
