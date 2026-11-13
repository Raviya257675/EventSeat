
package com.eventseat.server;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.PrintWriter;
import java.net.InetSocketAddress;
import java.net.Socket;

public class NetworkReliabilityTestMain {

    private static final String HOST = "localhost";
    private static int port = 5000;

    public static void main(String[] args) throws Exception {
        EventManager fixture = null;
        java.net.ServerSocket listener = null;
        Thread acceptor = null;
        java.util.List<ClientHandler> handlers = new java.util.concurrent.CopyOnWriteArrayList<>();
        if (args.length > 0 && args[0].equals("--embedded")) {
            java.util.Map<String, com.eventseat.model.Event> events = new java.util.LinkedHashMap<>();
            for (String id : java.util.List.of("E001", "E002", "E003")) {
                var event = new com.eventseat.model.Event(id, "Network test", "Test", "Test");
                event.addSeat(new com.eventseat.model.Seat("A1"));
                events.put(id, event);
            }
            fixture = new EventManager(events);
            listener = new java.net.ServerSocket(0, 50, java.net.InetAddress.getLoopbackAddress());
            port = listener.getLocalPort();
            final var server = listener;
            final var manager = fixture;
            acceptor = new Thread(() -> {
                try {
                    while (!server.isClosed()) {
                        ClientHandler handler = new ClientHandler(server.accept(), manager);
                        handlers.add(handler); handler.start();
                    }
                } catch (IOException ignored) { }
            }, "network-regression-acceptor");
            acceptor.start();
        }
        try {

            System.out.println("==================================");
            System.out.println("EVENTSEAT NETWORK RELIABILITY TEST");
            System.out.println("==================================");

            String[][] tests = {
                    {
                            "Get Events",
                            "GET_EVENTS",
                            "EVENTS:"
                    },
                    {
                            "Get E001 Seats",
                            "GET_SEATS:E001",
                            "SEATS:E001:"
                    },
                    {
                            "Get E002 Seats",
                            "GET_SEATS:E002",
                            "SEATS:E002:"
                    },
                    {
                            "Get E003 Seats",
                            "GET_SEATS:E003",
                            "SEATS:E003:"
                    },
                    {
                            "Invalid Event",
                            "GET_SEATS:E999",
                            "EVENT_NOT_FOUND"
                    },
                    {
                            "Invalid Booking Event",
                            "BOOK:E999:A1:TestUser",
                            "EVENT_NOT_FOUND"
                    },
                    {
                            "Invalid Seat",
                            "BOOK:E002:Z99:TestUser",
                            "SEAT_NOT_FOUND"
                    },
                    {
                            "Invalid Booking ID",
                            "GET_BOOKING:abc:TestUser",
                            "INVALID_BOOKING_REQUEST"
                    },
                    {
                            "Invalid Cancellation ID",
                            "CANCEL_BOOKING:abc:TestUser",
                            "INVALID_CANCEL_REQUEST"
                    },
                    {
                            "Unknown Command",
                            "HELLO_SERVER",
                            "UNKNOWN_COMMAND"
                    }
            };

            int passed = 0;
            int failed = 0;

            for (String[] test : tests) {

                String testName = test[0];
                String command = test[1];
                String expected = test[2];

                System.out.println("\nTest: " + testName);
                System.out.println("Command: " + command);

                try {

                    String response = sendCommand(command);

                    boolean success = response != null &&
                            response.startsWith(expected);

                    if ("GET_EVENTS".equals(command)) {
                        success = success &&
                                response.contains("E001|") &&
                                response.contains("E002|") &&
                                response.contains("E003|");
                    }

                    if (success) {

                        passed++;
                        System.out.println("Result: PASS");

                    } else {

                        failed++;
                        System.out.println("Result: FAIL");
                        System.out.println("Expected: " + expected);
                        System.out.println("Received: " + response);
                    }

                } catch (IOException e) {

                    failed++;

                    System.out.println("Result: FAIL");
                    System.out.println(
                            "Network error: " + e.getMessage()
                    );
                }
            }

            System.out.println("\n==================================");
            System.out.println("FINAL TEST RESULTS");
            System.out.println("==================================");

            System.out.println("Total Tests: " + tests.length);
            System.out.println("Passed: " + passed);
            System.out.println("Failed: " + failed);

            if (failed == 0) {

                System.out.println(
                        "\nALL NETWORK RELIABILITY TESTS PASSED!"
                );

            } else {

                System.out.println(
                        "\nSOME TESTS FAILED. CHECK RESULTS ABOVE."
                );
            }
            if (failed != 0) throw new AssertionError(failed + " network reliability tests failed");
        } finally {
            if (listener != null) listener.close();
            if (acceptor != null) acceptor.join(2000);
            if (fixture != null) fixture.close();
            for (ClientHandler handler : handlers) handler.join(3000);
        }
    }

    private static String sendCommand(
            String command
    ) throws IOException {

        try (Socket socket = new Socket()) {

            socket.connect(
                    new InetSocketAddress(HOST, port),
                    3000
            );

            socket.setSoTimeout(5000);

            try (
                    PrintWriter writer = new PrintWriter(
                            socket.getOutputStream(),
                            true
                    );

                    BufferedReader reader = new BufferedReader(
                            new InputStreamReader(
                                    socket.getInputStream()
                            )
                    )
            ) {

                writer.println(command);

                if (writer.checkError()) {

                    throw new IOException(
                            "Could not send command."
                    );
                }

                return TcpTestClient.readApplicationLine(reader, writer);
            }
        }
    }
}
