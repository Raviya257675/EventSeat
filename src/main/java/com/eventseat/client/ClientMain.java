package com.eventseat.client;

import java.io.IOException;
import java.util.Scanner;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/** Console client shares the desktop client's single-reader, heartbeat-aware transport. */
public class ClientMain {
    public static void main(String[] args) {
        CountDownLatch farewell = new CountDownLatch(1);
        ServerConnection connection = new ServerConnection(message -> {
            System.out.println("\nServer says: " + message);
            if (message.equals("Goodbye!") || message.startsWith("CONNECTION_ERROR:")) farewell.countDown();
        });
        try (Scanner scanner = new Scanner(System.in)) {
            connection.connect("localhost", 5000);
            System.out.println("Commands: GET_EVENTS, GET_EVENT, GET_SEATS, BOOK:E001:A1:Name, EXIT");
            while (connection.isConnected() && scanner.hasNextLine()) {
                String message = scanner.nextLine();
                if (!connection.send(message)) break;
                if (message.equalsIgnoreCase("EXIT")) {
                    farewell.await(1, TimeUnit.SECONDS);
                    break;
                }
            }
        } catch (IOException e) {
            System.err.println("Cannot connect to server: " + e.getMessage());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } finally { connection.close(); }
    }
}
