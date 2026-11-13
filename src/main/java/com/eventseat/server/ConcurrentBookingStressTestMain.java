
package com.eventseat.server;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.PrintWriter;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

public class ConcurrentBookingStressTestMain {

    private static final String HOST = "localhost";
    private static final int PORT = 5000;

    // Number of simulated clients
    private static final int CLIENT_COUNT = 10;

    // Change these to an available seat
    private static final String EVENT_ID = "E003";
    private static final String SEAT_NUMBER = "B4";

    public static void main(String[] args) throws Exception {

        System.out.println("================================");
        System.out.println("EVENTSEAT CONCURRENT BOOKING TEST");
        System.out.println("================================");

        System.out.println("Event: " + EVENT_ID);
        System.out.println("Seat: " + SEAT_NUMBER);
        System.out.println("Clients: " + CLIENT_COUNT);

        ExecutorService executor =
                Executors.newFixedThreadPool(CLIENT_COUNT);

        // Wait until clients are ready
        CountDownLatch ready =
                new CountDownLatch(CLIENT_COUNT);

        // Release all clients at once
        CountDownLatch start =
                new CountDownLatch(1);

        List<Future<String>> results =
                new ArrayList<>();

        for (int i = 1; i <= CLIENT_COUNT; i++) {

            final int clientId = i;

            Future<String> result = executor.submit(() -> {

                boolean readySignaled = false;

                try (Socket socket = new Socket()) {

                    socket.connect(
                            new InetSocketAddress(HOST, PORT),
                            3000
                    );

                    socket.setSoTimeout(15000);

                    BufferedReader reader =
                            new BufferedReader(
                                    new InputStreamReader(
                                            socket.getInputStream()
                                    )
                            );

                    PrintWriter writer =
                            new PrintWriter(
                                    socket.getOutputStream(),
                                    true
                            );

                    // This client is connected
                    ready.countDown();
                    readySignaled = true;

                    // Wait for all clients
                    if (!start.await(15, TimeUnit.SECONDS)) {

                        return "Client " + clientId
                                + " -> START_TIMEOUT";
                    }

                    String customerName =
                            "StressTestUser" + clientId;

                    String command =
                            "BOOK:" +
                                    EVENT_ID + ":" +
                                    SEAT_NUMBER + ":" +
                                    customerName;

                    // Send booking request
                    writer.println(command);

                    if (writer.checkError()) {

                        return "Client " + clientId
                                + " -> SEND_ERROR";
                    }

                    // Receive response
                    String response = TcpTestClient.readApplicationLine(reader, writer);

                    if (response == null) {

                        return "Client " + clientId
                                + " -> NO_RESPONSE";
                    }

                    return "Client " + clientId
                            + " -> " + response;

                } catch (Exception e) {

                    return "Client " + clientId
                            + " -> ERROR: "
                            + e.getClass().getSimpleName();

                } finally {

                    if (!readySignaled) {
                        ready.countDown();
                    }
                }
            });

            results.add(result);
        }

        System.out.println("\nConnecting clients...");

        boolean allReady =
                ready.await(20, TimeUnit.SECONDS);

        if (!allReady) {

            System.out.println(
                    "Warning: Some clients did not become ready."
            );
        }

        System.out.println("Sending booking requests...\n");

        // Start all waiting clients
        start.countDown();

        int successCount = 0;
        int alreadyBookedCount = 0;
        int otherCount = 0;

        for (Future<String> future : results) {

            String result =
                    future.get(25, TimeUnit.SECONDS);

            System.out.println(result);

            if (result.contains(
                    " -> BOOKING_SUCCESS:"
                            + EVENT_ID + ":"
                            + SEAT_NUMBER + ":"
            )) {

                successCount++;

            } else if (result.endsWith(
                    "ALREADY_BOOKED:"
                            + EVENT_ID + ":"
                            + SEAT_NUMBER
            )) {

                alreadyBookedCount++;

            } else {

                otherCount++;
            }
        }

        executor.shutdown();

        System.out.println("\n================================");
        System.out.println("TEST RESULTS");
        System.out.println("================================");

        System.out.println(
                "Successful bookings: " + successCount
        );

        System.out.println(
                "Already booked: " + alreadyBookedCount
        );

        System.out.println(
                "Other responses/errors: " + otherCount
        );

        if (successCount == 1
                && alreadyBookedCount == CLIENT_COUNT - 1
                && otherCount == 0) {

            System.out.println(
                    "\nTEST PASSED - Double booking prevented!"
            );

        } else {

            System.out.println(
                    "\nTEST DID NOT PASS."
            );

            System.out.println(
                    "Check the server, seat status and responses."
            );
        }
    }
}
