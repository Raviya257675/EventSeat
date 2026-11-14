package com.eventseat.client;

import javafx.application.Platform;
import javafx.scene.control.Button;
import javafx.stage.Stage;
import java.lang.reflect.*;
import java.util.Map;
import java.util.concurrent.*;

/** Uses the real EventSeatApp seat screen and ServerConnection with an isolated test server. */
public final class WebJavaFxPeerCheck {
    private static EventSeatApp app;
    private static Field field(String name) throws Exception { var f = EventSeatApp.class.getDeclaredField(name); f.setAccessible(true); return f; }
    private static void fx(Callable<Void> task) throws Exception {
        FutureTask<Void> future = new FutureTask<>(task); Platform.runLater(future); future.get(5,TimeUnit.SECONDS);
    }
    @SuppressWarnings("unchecked")
    public static void main(String[] args) throws Exception {
        Platform.startup(() -> Platform.setImplicitExit(false));
        Method handle = EventSeatApp.class.getDeclaredMethod("handleServerMessage",String.class); handle.setAccessible(true);
        fx(() -> { app = new EventSeatApp(); field("stage").set(app,new Stage()); return null; });
        ServerConnection connection = new ServerConnection(line -> Platform.runLater(() -> { try { handle.invoke(app,line); } catch (Exception e) { throw new RuntimeException(e); } }));
        try {
            connection.connect("127.0.0.1", Integer.parseInt(args[0]));
            fx(() -> {
                field("serverConnection").set(app,connection);
                Class<?> info = Class.forName("com.eventseat.client.EventSeatApp$EventInfo");
                var constructor = info.getDeclaredConstructor(String.class,String.class,String.class,String.class); constructor.setAccessible(true);
                var show = EventSeatApp.class.getDeclaredMethod("showEventScreen",info); show.setAccessible(true);
                show.invoke(app,constructor.newInstance("E001","Web integration concert","Test Hall","2026-12-12"));
                ((Stage)field("stage").get(app)).show(); return null;
            });
            Thread.sleep(1000);
            System.out.println("FX_READY"); System.out.flush();
            try (var reader = new java.io.BufferedReader(new java.io.InputStreamReader(System.in))) {
                String command;
                while ((command = reader.readLine()) != null && !command.equals("EXIT")) {
                    final String expected = command;
                    fx(() -> {
                        Button seat = ((Map<String,Button>)field("seatButtons").get(app)).get("A2");
                        if (seat == null) throw new AssertionError("Seat A2 was not loaded");
                        if (expected.equals("HOLD")) seat.fire();
                        else {
                            String color = expected.equals("HELD") ? "gold" : expected.equals("BOOKED") ? "lightcoral" : "lightgreen";
                            if (!seat.getStyle().contains(color)) throw new AssertionError("Expected " + expected + ", got " + seat.getStyle());
                            if (seat.isDisabled() != !expected.equals("AVAILABLE")) throw new AssertionError("Seat enabled state differs");
                        }
                        return null;
                    });
                    System.out.println("FX_PASS:" + expected); System.out.flush();
                }
            }
        } finally { connection.close(); fx(() -> { ((Stage)field("stage").get(app)).close(); return null; }); Platform.exit(); }
    }
}
