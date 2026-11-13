
package com.eventseat.server;

import com.eventseat.repository.BookingLookupRepository;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.PrintWriter;
import java.net.Socket;
import java.sql.SQLException;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CopyOnWriteArrayList;

public class ClientHandler extends Thread {

    private final Socket socket;
    private final EventManager eventManager;
    private final BookingLookupRepository lookupRepository =
            new BookingLookupRepository();

    private Thread sender;
    private Thread commandWorker;
    private final java.util.concurrent.BlockingQueue<String> commands =
            new java.util.concurrent.ArrayBlockingQueue<>(512);
    private volatile PrintWriter writer;

    boolean isConnectionClosed() { return socket.isClosed(); }
    void disconnect() { try { socket.close(); } catch (IOException ignored) { } }
    void enqueueMonitoring(String message) { send(message); }
    private volatile String subscribedEventId;
    private final java.util.concurrent.BlockingQueue<String> outgoing =
            new java.util.concurrent.ArrayBlockingQueue<>(512);

    private void send(String message) {
        if (!outgoing.offer(message)) {
            try { socket.close(); } catch (IOException ignored) { }
        }
    }

    private static final List<ClientHandler> clients =
            new CopyOnWriteArrayList<>();

    public ClientHandler(Socket socket, EventManager eventManager) {
        this.socket = socket;
        this.eventManager = eventManager;
    }

    @Override
    public void run() {

        // Close the socket first, so a blocked sender cannot delay hold cleanup.
        try (Socket client = socket) {
            BufferedReader reader = new BufferedReader(
                    new InputStreamReader(client.getInputStream()));
            PrintWriter output = new PrintWriter(client.getOutputStream(), true);

            writer = output;
            sender = new Thread(() -> {
                try {
                    while (!socket.isClosed()) {
                        String next = outgoing.poll(1, java.util.concurrent.TimeUnit.SECONDS);
                        if (next != null) {
                            output.println(next);
                            if (output.checkError()) { socket.close(); break; }
                            if (next.equals("Goodbye!")) break;
                        }
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } catch (IOException ignored) { }
            }, "eventseat-client-writer");
            sender.setDaemon(true);
            sender.start();
            clients.add(this);
            eventManager.heartbeat.register(this);
            commandWorker = new Thread(this::processCommands, "eventseat-command-worker");
            commandWorker.setDaemon(true);
            commandWorker.start();
            String message;
            while ((message = reader.readLine()) != null) {
                if (message.startsWith("PONG:")) {
                    eventManager.heartbeat.pong(this, message.substring(5));
                    continue;
                }
                if (socket.isClosed()) break;
                if (!commands.offer(message.trim())) {
                    disconnect(); // Bound queued work without blocking heartbeat reception.
                    break;
                }
            }

        } catch (IOException e) {

            System.out.println(
                    "Client connection closed: " + e.getMessage()
            );

        } finally {

            disconnect();
            eventManager.heartbeat.unregister(this);
            clients.remove(this);
            commands.clear();
            if (commandWorker != null) {
                commandWorker.interrupt();
                try { commandWorker.join(1000); }
                catch (InterruptedException e) { Thread.currentThread().interrupt(); }
            }
            if (sender != null) {
                sender.interrupt();
                try { sender.join(1000); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
            }
            outgoing.clear();
            writer = null;
            subscribedEventId = null;

            System.out.println(
                    "Client disconnected. Total clients: "
                            + clients.size()
            );
        }
    }

    private void processCommands() {
        boolean farewell = false;
        try {
            while (!socket.isClosed()) {
                String message = commands.take();
                if (socket.isClosed()) break;
                if (message.equalsIgnoreCase("EXIT")) { farewell = true; break; }
                processCommand(message);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } finally {
            // Only this worker owns terminal hold cleanup, after any in-flight operation.
            // Closing first wakes the reader on failure; EXIT keeps the socket for its farewell.
            if (!farewell) disconnect();
            eventManager.releaseOwner(this);
            if (farewell && !socket.isClosed()) {
                send("Goodbye!");
                try { sender.join(250); }
                catch (InterruptedException e) { Thread.currentThread().interrupt(); }
            }
            disconnect();
        }
    }

    private void processCommand(String message) {
        if (message.startsWith("HOLD:") || message.startsWith("RELEASE:")) {
            String[] parts = message.split(":", -1);
            if (parts.length != 3 || parts[1].isBlank() || parts[2].isBlank()) {
                send("INVALID_HOLD_REQUEST");
                return;
            }
            synchronized (eventManager) {
                if (socket.isClosed()) return;
                send(parts[0].equals("HOLD")
                        ? eventManager.holdSeat(parts[1], parts[2], this)
                        : eventManager.releaseSeat(parts[1], parts[2], this));
            }
        } else if (message.equals("LEAVE_SEATS")) {
            synchronized (eventManager) {
                if (socket.isClosed()) return;
                subscribedEventId = null;
                eventManager.releaseOwner(this);
                send("LEAVE_SUCCESS");
            }
        } else if (message.equalsIgnoreCase("GET_EVENTS")) {
            eventManager.releaseOwner(this);

            subscribedEventId = null;
            send(eventManager.getEvents());

        } else if (message.equalsIgnoreCase("GET_EVENT")) {

            send(
                    eventManager.getEventDetails("E001")
            );

        } else if (message.equalsIgnoreCase("GET_SEATS") ||
                message.toUpperCase(Locale.ROOT)
                        .startsWith("GET_SEATS:")) {

            String eventId = message.equalsIgnoreCase("GET_SEATS")
                    ? "E001"
                    : message.substring(
                    "GET_SEATS:".length()
            ).trim();

            synchronized (eventManager) {
                if (socket.isClosed()) return;

                if (!eventId.equals(subscribedEventId)) eventManager.releaseOwner(this);
                String seats =
                        eventManager.getSeatStatus(eventId);

                subscribedEventId =
                        seats.startsWith("SEATS:")
                                ? eventId : null;

                send(seats);
            }

        } else if (message.startsWith("GET_BOOKING:")) {

            String[] parts = message.split(":", 3);

            if (parts.length != 3 ||
                    parts[2].isBlank() ||
                    parts[2].length() > 100) {

                send("INVALID_BOOKING_REQUEST");
                return;
            }

            try {

                int bookingId =
                        Integer.parseInt(parts[1]);

                if (bookingId <= 0) {
                    send("INVALID_BOOKING_REQUEST");
                    return;
                }

                send(
                        lookupRepository.findBooking(
                                bookingId,
                                parts[2].trim()
                        )
                );

            } catch (NumberFormatException e) {

                send("INVALID_BOOKING_REQUEST");

            } catch (SQLException e) {

                send("DATABASE_ERROR");
            }

        } else if (message.startsWith("CANCEL_BOOKING:")) {

            String[] parts = message.split(":", 3);

            if (parts.length != 3 ||
                    parts[2].isBlank() ||
                    parts[2].length() > 100) {

                send("INVALID_CANCEL_REQUEST");
                return;
            }

            try {

                int bookingId =
                        Integer.parseInt(parts[1]);

                if (bookingId <= 0) {
                    send("INVALID_CANCEL_REQUEST");
                    return;
                }

                synchronized (eventManager) {
                    if (socket.isClosed()) return;

                    String reply =
                            eventManager.cancelBooking(
                                    bookingId,
                                    parts[2].trim()
                            );

                    send(reply);

                    if (reply.startsWith("CANCEL_SUCCESS:")) {

                        String[] result = reply.split(":", 4);

                        if (result.length == 4) {

                            broadcastToEvent(
                                    result[2],
                                    "SEAT_UPDATE:"
                                            + result[2] + ":"
                                            + result[3]
                                            + ":AVAILABLE"
                            );
                        }
                    }
                }

            } catch (NumberFormatException e) {

                send("INVALID_CANCEL_REQUEST");
            }

        } else if (message.toUpperCase(Locale.ROOT)
                .startsWith("BOOK:")) {

            String[] parts = message.split(":", 4);

            String eventId;
            String seatNumber;
            String customerName;

            if (parts.length == 4) {

                eventId = parts[1].trim();

                seatNumber = parts[2].trim()
                        .toUpperCase(Locale.ROOT);

                customerName = parts[3].trim();

            } else if (parts.length == 3) {

                // Support old single-event console command
                eventId = "E001";

                seatNumber = parts[1].trim()
                        .toUpperCase(Locale.ROOT);

                customerName = parts[2].trim();

            } else {

                send("INVALID_BOOKING_REQUEST");
                return;
            }

            if (eventId.isBlank() ||
                    seatNumber.isBlank() ||
                    customerName.isBlank() ||
                    customerName.length() > 100 ||
                    customerName.contains(":")) {

                send("INVALID_BOOKING_REQUEST");
                return;
            }

            synchronized (eventManager) {
                if (socket.isClosed()) return;

                String reply = eventManager.bookSeat(
                        eventId,
                        seatNumber,
                        customerName, this
                );

                if (reply.startsWith("BOOKING_SUCCESS:")) {

                    String[] result = reply.split(":", 3);

                    if (result.length == 3) {

                        send(
                                "BOOKING_SUCCESS:"
                                        + eventId + ":"
                                        + seatNumber + ":"
                                        + result[2]
                        );

                        broadcastToEvent(
                                eventId,
                                "SEAT_UPDATE:"
                                        + eventId + ":"
                                        + seatNumber
                                        + ":BOOKED"
                        );

                    } else {

                        send("DATABASE_ERROR");
                    }

                } else if (reply.startsWith("ALREADY_BOOKED:")) {

                    send(
                            "ALREADY_BOOKED:"
                                    + eventId + ":"
                                    + seatNumber
                    );
                    broadcastToEvent(eventId,
                            "SEAT_UPDATE:" + eventId + ":" + seatNumber + ":BOOKED");

                } else {

                    send(reply);
                }
            }

        } else {

            send("UNKNOWN_COMMAND");
        }
    }

    static void broadcastToEvent(
            String eventId,
            String message
    ) {

        for (ClientHandler client : clients) {

            PrintWriter clientWriter = client.writer;

            if (clientWriter != null &&
                    eventId.equals(client.subscribedEventId)) {

                client.send(message);
            }
        }
    }
}
