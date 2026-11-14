package com.eventseat.server;

import com.eventseat.util.DatabaseConnection;
import java.net.*;
import java.sql.*;
import java.util.*;

/** Opt-in integration fixture: real repositories against a disposable MySQL schema.
 * Never inserts/updates/deletes rows in the configured application schema.
 * Stop with a newline on stdin so the disposable schema is dropped.
 */
public final class WebDatabaseFixture {
    public static void main(String[] args) throws Exception {
        String schema = "eventseat_web_test_" + UUID.randomUUID().toString().replace("-", "");
        Properties settings = new Properties();
        try (var input = WebDatabaseFixture.class.getClassLoader().getResourceAsStream("db.properties")) {
            if (input == null) throw new IllegalStateException("Local db.properties required for isolated MySQL tests");
            settings.load(input);
        }
        // Use the existing account solely to create/drop a uniquely named test schema.
        try (Connection admin = DatabaseConnection.getConnection(); Statement sql = admin.createStatement()) {
            sql.executeUpdate("CREATE DATABASE " + schema);
            try {
                sql.executeUpdate("CREATE TABLE " + schema + ".events (event_id VARCHAR(20) PRIMARY KEY, event_name VARCHAR(100) NOT NULL, venue VARCHAR(100) NOT NULL, event_date DATE NOT NULL)");
                sql.executeUpdate("CREATE TABLE " + schema + ".seats (seat_id INT AUTO_INCREMENT PRIMARY KEY, event_id VARCHAR(20) NOT NULL, seat_number VARCHAR(10) NOT NULL, status VARCHAR(20) NOT NULL DEFAULT 'AVAILABLE', UNIQUE(event_id,seat_number))");
                sql.executeUpdate("CREATE TABLE " + schema + ".bookings (booking_id INT AUTO_INCREMENT PRIMARY KEY, event_id VARCHAR(20) NOT NULL, seat_number VARCHAR(10) NOT NULL, customer_name VARCHAR(100), booking_time TIMESTAMP DEFAULT CURRENT_TIMESTAMP, status VARCHAR(20) NOT NULL DEFAULT 'ACTIVE')");
                sql.executeUpdate("INSERT INTO " + schema + ".events VALUES ('E001','Web integration concert','Test Hall','2026-12-12'),('E002','Second event','Test Theatre','2026-12-18')");
                sql.executeUpdate("INSERT INTO " + schema + ".seats(event_id,seat_number) VALUES ('E001','A1'),('E001','A2'),('E001','B1'),('E002','A1')");
                String url = settings.getProperty("db.url");
                int databaseStart = url.indexOf('/', "jdbc:mysql://".length());
                if (!url.startsWith("jdbc:mysql://") || databaseStart < 0) throw new IllegalStateException("Fixture requires a conventional jdbc:mysql://host/database URL");
                int query = url.indexOf('?',databaseStart);
                String testUrl = url.substring(0,databaseStart + 1) + schema + (query < 0 ? "" : url.substring(query));
                // Test-process-only override; production DatabaseConnection stays untouched.
                var field = DatabaseConnection.class.getDeclaredField("properties");
                field.setAccessible(true);
                ((Properties)field.get(null)).setProperty("db.url",testUrl);
                try (EventManager manager = new EventManager(); ServerSocket listener = new ServerSocket(args.length == 0 ? 0 : Integer.parseInt(args[0]),50,InetAddress.getByName("127.0.0.1"))) {
                    List<ClientHandler> clients = new java.util.concurrent.CopyOnWriteArrayList<>();
                    Thread acceptor = new Thread(() -> {
                        try { while (!listener.isClosed()) { var client = new ClientHandler(listener.accept(),manager); clients.add(client); client.start(); } }
                        catch (java.io.IOException ignored) { }
                    });
                    acceptor.start();
                    System.out.println("FIXTURE_READY:" + listener.getLocalPort());
                    System.out.flush();
                    System.in.read();
                    listener.close(); manager.close(); acceptor.join(2000);
                    for (var client : clients) client.join(3000);
                    try (var check = sql.executeQuery("SELECT COUNT(*) FROM " + schema + ".bookings WHERE status='CANCELLED'")) {
                        check.next(); System.out.println("FIXTURE_CANCELLED_ROWS:" + check.getInt(1));
                    }
                }
            } finally { sql.executeUpdate("DROP DATABASE " + schema); System.out.println("FIXTURE_SCHEMA_DROPPED"); }
        }
    }
}
