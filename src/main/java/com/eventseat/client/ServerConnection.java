
package com.eventseat.client;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.PrintWriter;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.util.function.Consumer;

public class ServerConnection {

    private final java.util.concurrent.ExecutorService sender =
            new java.util.concurrent.ThreadPoolExecutor(0, 1, 1,
                    java.util.concurrent.TimeUnit.SECONDS,
                    new java.util.concurrent.ArrayBlockingQueue<>(512), task -> {
                Thread thread = new Thread(task, "eventseat-sender");
                thread.setDaemon(true);
                return thread;
            });
    // Identity changes only when a replacement connection is installed, never on EOF.
    private volatile long generation;
    public long connectionGeneration() { return generation; }
    private Socket socket;
    private PrintWriter writer;

    private volatile boolean connected = false;
    private volatile boolean closing = false;

    private final Object lock = new Object();

    private final Consumer<String> messageHandler;

    public ServerConnection(
            Consumer<String> messageHandler
    ) {
        this.messageHandler = messageHandler;
    }

    // -----------------------------------------
    // CONNECT TO SERVER
    // -----------------------------------------

    public void connect(
            String host,
            int port
    ) throws IOException {

        synchronized (lock) {

            if (connected) {
                return;
            }
        }

        Socket newSocket = new Socket();

        try {

            // Wait maximum 3 seconds for connection
            newSocket.connect(
                    new InetSocketAddress(host, port),
                    3000
            );

            BufferedReader reader =
                    new BufferedReader(
                            new InputStreamReader(
                                    newSocket.getInputStream()
                            )
                    );

            PrintWriter newWriter =
                    new PrintWriter(
                            newSocket.getOutputStream(),
                            true
                    );

            synchronized (lock) {

                if (connected) { newSocket.close(); return; }
                generation++;
                socket = newSocket;
                writer = newWriter;

                connected = true;
                closing = false;
            }

            System.out.println(
                    "Connected to EventSeat server."
            );

            // Background thread receives messages
            Thread listenerThread = new Thread(
                    () -> listenForMessages(reader, newSocket)
            );

            listenerThread.setDaemon(true);
            listenerThread.start();

        } catch (IOException e) {

            try {
                newSocket.close();
            } catch (IOException ignored) {
            }

            throw e;
        }
    }

    // -----------------------------------------
    // LISTEN FOR SERVER MESSAGES
    // -----------------------------------------

    private void listenForMessages(
            BufferedReader reader,
            Socket activeSocket
    ) {

        try {

            String message;

            while ((message = reader.readLine()) != null) {

                synchronized (lock) {
                    if (socket != activeSocket) return;
                    if (message.startsWith("PING:")) {
                        if (message.length() > 5) send("PONG:" + message.substring(5));
                    } else messageHandler.accept(message);
                }
            }

        } catch (IOException e) {

            System.out.println(
                    "Connection interrupted: "
                            + e.getMessage()
            );

        } finally {

            handleDisconnection(activeSocket);
        }
    }

    // -----------------------------------------
    // DETECT UNEXPECTED DISCONNECTION
    // -----------------------------------------

    private void handleDisconnection(
            Socket activeSocket
    ) {

        boolean notifyClient;

        synchronized (lock) {

            if (socket != activeSocket) {
                return;
            }

            notifyClient = connected && !closing;

            connected = false;
            writer = null;
            socket = null;
            if (notifyClient) messageHandler.accept("CONNECTION_ERROR:Server disconnected");
        }

        try {
            activeSocket.close();
        } catch (IOException ignored) {
        }


    }

    // -----------------------------------------
    // SEND MESSAGE TO SERVER
    // -----------------------------------------

    public boolean send(String message) {

        Socket activeSocket;
        PrintWriter activeWriter;

        synchronized (lock) {

            if (!connected ||
                    socket == null ||
                    writer == null) {

                System.out.println(
                        "Cannot send: Server not connected."
                );

                return false;
            }

            activeSocket = socket;
            activeWriter = writer;
        }

        try { sender.execute(() -> {
            synchronized (lock) {
                if (socket != activeSocket) return;
            }
            activeWriter.println(message);
            if (activeWriter.checkError()) handleDisconnection(activeSocket);
        }); } catch (java.util.concurrent.RejectedExecutionException e) {
            handleDisconnection(activeSocket);
            return false;
        }
        return true;
    }

    // -----------------------------------------
    // CHECK CONNECTION STATUS
    // -----------------------------------------

    public boolean isConnected() {

        return connected;
    }

    // -----------------------------------------
    // CLOSE CONNECTION SAFELY
    // -----------------------------------------

    public void close() {

        Socket activeSocket;
        PrintWriter activeWriter;

        synchronized (lock) {

            closing = true;
            connected = false;

            activeSocket = socket;
            activeWriter = writer;

            socket = null;
            writer = null;
        }

        // Closing the socket releases holds even if an outbound write is blocked.

        if (activeSocket != null) {

            try {

                activeSocket.close();

            } catch (IOException e) {

                System.out.println(
                        "Error closing socket."
                );
            }
        }

        System.out.println(
                "Server connection closed."
        );
    }
}
