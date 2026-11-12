package com.eventseat.server;

import java.io.IOException;
import java.net.ServerSocket;
import java.net.Socket;

public class ServerMain {

    public static void main(String[] args) {

        int port = 5000;

        EventManager eventManager = new EventManager();

        Runtime.getRuntime().addShutdownHook(new Thread(eventManager::close));
        try (ServerSocket serverSocket = new ServerSocket(port)) {

            System.out.println("EventSeat Server Started");
            System.out.println("Listening on port " + port);

            while (true) {

                System.out.println("Waiting for a client...");

                Socket clientSocket = serverSocket.accept();

                System.out.println(
                        "New client connected: " +
                                clientSocket.getInetAddress()
                );

                ClientHandler clientHandler =
                        new ClientHandler(
                                clientSocket,
                                eventManager
                        );

                clientHandler.start();
            }

        } catch (IOException e) {
            e.printStackTrace();
        } finally {
            eventManager.close();
        }
    }
}