package com.eventseat.server;

import java.io.*;
import java.net.*;
import java.util.*;
import java.util.concurrent.*;

/** Shared standalone-test peer: continuously reads so idle peers still answer heartbeats. */
class TcpTestClient implements AutoCloseable {
    final Socket socket;
    final PrintWriter writer;
    final List<String> updates = new CopyOnWriteArrayList<>();
    final BlockingQueue<String> messages = new LinkedBlockingQueue<>();
    final Thread readerThread;
    volatile boolean respond = true;
    volatile String lastPing;
    TcpTestClient(String host, int port) throws IOException {
        socket = new Socket();
        socket.connect(new InetSocketAddress(host, port), 3000);
        writer = new PrintWriter(socket.getOutputStream(), true);
        BufferedReader reader = new BufferedReader(new InputStreamReader(socket.getInputStream()));
        readerThread = new Thread(() -> {
            try {
                String line;
                while ((line = reader.readLine()) != null) {
                    synchronized (messages) {
                        if (line.startsWith("PING:")) {
                            lastPing = line.substring(5);
                            if (respond) writer.println("PONG:" + lastPing);
                        }
                        messages.add(line);
                    }
                }
            } catch (IOException ignored) { }
            finally { messages.add("EOF"); }
        }, "tcp-test-reader");
        readerThread.start();
    }
    void pauseResponses() {
        synchronized (messages) { messages.clear(); respond = false; }
    }
    String await(String prefix, long millis) throws IOException {
        long end = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(millis);
        try {
            while (true) {
                String line = messages.poll(Math.max(0, end - System.nanoTime()), TimeUnit.NANOSECONDS);
                if (line == null) throw new SocketTimeoutException("Missing " + prefix);
                if (line.startsWith(prefix)) return line;
                if (line.equals("EOF")) throw new EOFException("Missing " + prefix);
                if (line.startsWith("SEAT_UPDATE:")) updates.add(line);
            }
        } catch (InterruptedException e) { Thread.currentThread().interrupt(); throw new IOException(e); }
    }
    static String readApplicationLine(BufferedReader reader, PrintWriter writer) throws IOException {
        String line;
        while ((line = reader.readLine()) != null) {
            if (line.startsWith("PING:")) writer.println("PONG:" + line.substring(5));
            else if (!line.startsWith("CLIENT_STATS:") && !line.startsWith("LATENCY:")) return line;
        }
        return null;
    }
    String request(String command) throws IOException {
        writer.println(command);
        return response();
    }
    String response() throws IOException {
        long end = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        try {
            while (true) {
                String line = messages.poll(Math.max(0, end - System.nanoTime()), TimeUnit.NANOSECONDS);
                if (line == null) throw new SocketTimeoutException("Application reply");
                if (line.equals("EOF")) throw new EOFException("Application reply");
                if (line.startsWith("SEAT_UPDATE:")) updates.add(line);
                else if (!line.startsWith("PING:") && !line.startsWith("LATENCY:") && !line.startsWith("CLIENT_STATS:")) return line;
            }
        } catch (InterruptedException e) { Thread.currentThread().interrupt(); throw new IOException(e); }
    }
    void update(String expected, int timeout) throws IOException {
        if (updates.remove(expected)) return;
        await(expected, timeout);
    }
    public void close() throws IOException {
        socket.close();
        try { readerThread.join(2000); }
        catch (InterruptedException e) { Thread.currentThread().interrupt(); }
    }
}
