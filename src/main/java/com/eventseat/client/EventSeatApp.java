
package com.eventseat.client;

import javafx.application.Application;
import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Scene;
import javafx.scene.control.Alert;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonType;
import javafx.scene.control.Label;
import javafx.scene.control.TextArea;
import javafx.scene.control.TextField;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.VBox;
import javafx.stage.Stage;

import java.io.IOException;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

public class EventSeatApp extends Application {

    private static class EventInfo {
        final String id, name, venue, date;

        EventInfo(String id, String name,
                  String venue, String date) {
            this.id = id;
            this.name = name;
            this.venue = venue;
            this.date = date;
        }
    }

    private Stage stage;
    private ServerConnection serverConnection;
    private String currentScreen = "HOME";

    private final AtomicBoolean connecting =
            new AtomicBoolean(false);

    private volatile boolean shuttingDown = false;

    private Label connectionStatusLabel;
    private Button reconnectButton;

    private VBox eventsContainer;
    private GridPane seatGrid;
    private EventInfo selectedEvent;
    private String selectedSeat;
    private boolean holdOwned;
    private long holdExpiry;
    private final java.util.ArrayDeque<String> holdRequests = new java.util.ArrayDeque<>();
    private String desiredHold;
    private final javafx.animation.Timeline holdClock = new javafx.animation.Timeline(
            new javafx.animation.KeyFrame(javafx.util.Duration.millis(200), e -> tickHold()));

    private void tickHold() {
        if (!holdOwned) return;
        long remaining = Math.max(0, (holdExpiry - System.currentTimeMillis() + 999) / 1000);
        selectedSeatLabel.setText("Selected Seat: " + selectedSeat + " - " + remaining + "s remaining");
        if (remaining == 0) {
            clearHold("Hold expired. Select an available seat again.");
        }
    }

    // Called only on the JavaFX thread, like all network-driven UI handlers.
    private void clearHold(String status) {
        desiredHold = null;
        holdOwned = false;
        holdExpiry = 0;
        selectedSeat = null;
        holdClock.stop();
        if (selectedSeatLabel != null) selectedSeatLabel.setText("Selected Seat: None");
        if (bookButton != null) bookButton.setDisable(true);
        if (bookingStatusLabel != null) bookingStatusLabel.setText(status);
    }

    private void leaveSeats() {
        if ("SEATS".equals(currentScreen) && isConnected()) serverConnection.send("LEAVE_SEATS");
        clearHold("");
    }

    private boolean canBook() {
        return holdOwned && selectedSeat != null && System.currentTimeMillis() < holdExpiry
                && isConnected() && !bookingPending;
    }

    private Label selectedSeatLabel;
    private Label bookingStatusLabel;
    private Button bookButton;

    private final Map<String, Button> seatButtons =
            new HashMap<>();

    private boolean bookingPending = false;
    private String pendingCustomerName = "";
    private EventInfo pendingEvent;

    private TextArea bookingLookupResultArea;
    private Button cancelButton;

    private String requestedBookingId = "";
    private String requestedCustomerName = "";
    private String verifiedBookingId = "";
    private String verifiedCustomerName = "";

    // -----------------------------------------
    // APPLICATION START
    // -----------------------------------------

    @Override
    public void start(Stage primaryStage) {

        stage = primaryStage;
        holdClock.setCycleCount(javafx.animation.Animation.INDEFINITE);

        serverConnection = new ServerConnection(
                message -> Platform.runLater(
                        () -> handleServerMessage(message)
                )
        );

        showHomeScreen();

        stage.setTitle("EventSeat");

        stage.setOnCloseRequest(event -> {
            shuttingDown = true;
            serverConnection.close();
        });

        stage.show();

        // Connect without blocking JavaFX UI
        connectAsync();
    }

    private boolean isConnected() {

        return serverConnection != null &&
                serverConnection.isConnected();
    }

    // -----------------------------------------
    // CONNECTION STATUS BAR
    // -----------------------------------------

    private HBox createConnectionBar() {

        connectionStatusLabel = new Label();

        reconnectButton = new Button("Reconnect");

        reconnectButton.setOnAction(event -> {
            connectAsync();
        });

        HBox bar = new HBox(
                12,
                connectionStatusLabel,
                reconnectButton
        );

        bar.setAlignment(Pos.CENTER);

        updateConnectionBar();

        return bar;
    }

    private void updateConnectionBar() {

        if (connectionStatusLabel == null ||
                reconnectButton == null) {
            return;
        }

        if (connecting.get()) {

            connectionStatusLabel.setText(
                    "Server: Connecting..."
            );

            connectionStatusLabel.setStyle(
                    "-fx-text-fill: darkorange;"
            );

        } else if (isConnected()) {

            connectionStatusLabel.setText(
                    "Server: Connected"
            );

            connectionStatusLabel.setStyle(
                    "-fx-text-fill: green;"
            );

        } else {

            connectionStatusLabel.setText(
                    "Server: Disconnected"
            );

            connectionStatusLabel.setStyle(
                    "-fx-text-fill: red;"
            );
        }

        reconnectButton.setDisable(
                connecting.get() || isConnected()
        );
    }

    // -----------------------------------------
    // ASYNCHRONOUS CONNECTION
    // -----------------------------------------

    private void connectAsync() {

        if (shuttingDown ||
                isConnected() ||
                !connecting.compareAndSet(false, true)) {
            return;
        }

        updateConnectionBar();

        Thread worker = new Thread(() -> {

            String error = null;

            try {

                serverConnection.connect(
                        "localhost",
                        5000
                );

            } catch (IOException e) {

                error = e.getMessage();
            }

            final String failure = error;

            if (shuttingDown) {
                serverConnection.close();
                connecting.set(false);
                return;
            }

            Platform.runLater(() -> {

                connecting.set(false);

                if (shuttingDown) {
                    serverConnection.close();
                    return;
                }

                updateConnectionBar();

                if (isConnected()) {

                    refreshAfterConnection();

                } else {

                    String note =
                            "Cannot connect to server. " +
                                    "Start ServerMain and click Reconnect.";

                    if (failure != null) {
                        System.out.println(
                                "Connect failed: " + failure
                        );
                    }

                    showConnectionNotice(note);
                }
            });

        }, "eventseat-connector");

        worker.setDaemon(true);
        worker.start();
    }

    // -----------------------------------------
    // REFRESH AFTER RECONNECTING
    // -----------------------------------------

    private void refreshAfterConnection() {

        if ("EVENTS".equals(currentScreen)) {

            eventsContainer.getChildren().setAll(
                    new Label("Loading events...")
            );

            serverConnection.send("GET_EVENTS");

        } else if ("SEATS".equals(currentScreen) &&
                selectedEvent != null) {

            bookingPending = false;
            selectedSeat = null;

            selectedSeatLabel.setText(
                    "Selected Seat: None"
            );

            bookButton.setDisable(true);

            seatButtons.values().forEach(
                    button -> button.setDisable(true)
            );

            bookingStatusLabel.setText(
                    "Connected. Refreshing seats..."
            );

            serverConnection.send(
                    "GET_SEATS:" + selectedEvent.id
            );

        } else if ("LOOKUP".equals(currentScreen)) {

            verifiedBookingId = "";
            verifiedCustomerName = "";

            if (cancelButton != null) {
                cancelButton.setDisable(true);
            }

            if (bookingLookupResultArea != null) {

                bookingLookupResultArea.setText(
                        "Reconnected. Search for your booking again."
                );
            }
        }
    }

    // -----------------------------------------
    // DISPLAY CONNECTION NOTICE
    // -----------------------------------------

    private void showConnectionNotice(String note) {

        if ("EVENTS".equals(currentScreen) &&
                eventsContainer != null) {

            eventsContainer.getChildren().setAll(
                    new Label(note)
            );

        } else if ("SEATS".equals(currentScreen) &&
                bookingStatusLabel != null) {

            bookingStatusLabel.setText(note);

        } else if ("LOOKUP".equals(currentScreen) &&
                bookingLookupResultArea != null) {

            bookingLookupResultArea.setText(note);
        }
    }

    // -----------------------------------------
    // HANDLE CONNECTION LOSS
    // -----------------------------------------

    private void onConnectionLost() {
        clearHold("Server disconnected. Click Reconnect after restarting it.");
        holdRequests.clear();

        updateConnectionBar();

        bookingPending = false;

        verifiedBookingId = "";
        verifiedCustomerName = "";

        if (cancelButton != null) {
            cancelButton.setDisable(true);
        }

        if (bookButton != null) {
            bookButton.setDisable(true);
        }

        seatButtons.values().forEach(
                button -> button.setDisable(true)
        );

        showConnectionNotice(
                "Server disconnected. " +
                        "Click Reconnect after restarting it."
        );
    }

    // -----------------------------------------
    // HOME SCREEN
    // -----------------------------------------

    private void showHomeScreen() {
        leaveSeats();

        currentScreen = "HOME";
        selectedEvent = null;
        bookingLookupResultArea = null;
        seatButtons.clear();

        Label title = new Label("EventSeat");

        title.setStyle(
                "-fx-font-size: 28px;" +
                        "-fx-font-weight: bold;"
        );

        Label subtitle = new Label(
                "Real-Time Event Seat Booking System"
        );

        HBox connection = createConnectionBar();

        Button viewEvents =
                new Button("View Events");

        viewEvents.setPrefWidth(190);

        viewEvents.setOnAction(event -> {
            showEventsListScreen();
        });

        Button findBooking =
                new Button("Find My Booking");

        findBooking.setPrefWidth(190);

        findBooking.setOnAction(event -> {
            showBookingLookupScreen();
        });

        VBox layout = new VBox(
                20,
                title,
                subtitle,
                connection,
                viewEvents,
                findBooking
        );

        layout.setAlignment(Pos.CENTER);
        layout.setPadding(new Insets(30));

        stage.setScene(
                new Scene(layout, 720, 540)
        );
    }

    // -----------------------------------------
    // AVAILABLE EVENTS SCREEN
    // -----------------------------------------

    private void showEventsListScreen() {
        leaveSeats();

        currentScreen = "EVENTS";
        selectedEvent = null;
        bookingLookupResultArea = null;
        seatButtons.clear();

        Label title =
                new Label("Available Events");

        title.setStyle(
                "-fx-font-size: 24px;" +
                        "-fx-font-weight: bold;"
        );

        eventsContainer = new VBox(12);
        eventsContainer.setAlignment(Pos.CENTER);

        eventsContainer.getChildren().add(
                new Label("Loading events...")
        );

        Button back =
                new Button("Back to Home");

        back.setOnAction(event -> {
            showHomeScreen();
        });

        VBox layout = new VBox(
                25,
                createConnectionBar(),
                title,
                eventsContainer,
                back
        );

        layout.setAlignment(Pos.TOP_CENTER);
        layout.setPadding(new Insets(35));

        stage.setScene(
                new Scene(layout, 720, 570)
        );

        if (isConnected()) {

            serverConnection.send("GET_EVENTS");

        } else {

            eventsContainer.getChildren().setAll(
                    new Label("Server is not connected.")
            );
        }
    }

    // -----------------------------------------
    // DISPLAY EVENTS
    // -----------------------------------------

    private void displayEvents(String data) {

        if (!"EVENTS".equals(currentScreen) ||
                eventsContainer == null) {
            return;
        }

        eventsContainer.getChildren().clear();

        int count = 0;

        for (String row : data.split(";")) {

            if (row.isBlank()) {
                continue;
            }

            String[] parts =
                    row.split("\\|", 4);

            if (parts.length != 4) {
                continue;
            }

            EventInfo event = new EventInfo(
                    parts[0],
                    parts[1],
                    parts[2],
                    parts[3]
            );

            Button button = new Button(
                    "View Seats - " + event.id
            );

            button.setOnAction(action -> {
                showEventScreen(event);
            });

            VBox card = new VBox(
                    6,
                    new Label(event.name),
                    new Label("Venue: " + event.venue),
                    new Label("Date: " + event.date),
                    button
            );

            card.setAlignment(Pos.CENTER);
            card.setPadding(new Insets(12));

            card.setStyle(
                    "-fx-background-color: #f3f5f7;" +
                            "-fx-background-radius: 10;"
            );

            card.setMaxWidth(390);

            eventsContainer.getChildren().add(card);

            count++;
        }

        if (count == 0) {

            eventsContainer.getChildren().add(
                    new Label("No events found.")
            );
        }
    }

    // -----------------------------------------
    // EVENT SEAT SCREEN
    // -----------------------------------------

    private void showEventScreen(EventInfo event) {
        leaveSeats();

        currentScreen = "SEATS";
        selectedEvent = event;
        selectedSeat = null;

        seatButtons.clear();
        bookingLookupResultArea = null;

        Label title = new Label(event.name);

        title.setStyle(
                "-fx-font-size: 24px;" +
                        "-fx-font-weight: bold;"
        );

        Label venue =
                new Label("Venue: " + event.venue);

        Label date =
                new Label("Date: " + event.date);

        Label stageLabel = new Label("STAGE");

        stageLabel.setStyle(
                "-fx-font-size: 18px;" +
                        "-fx-font-weight: bold;" +
                        "-fx-padding: 10px 60px;" +
                        "-fx-border-color: black;"
        );

        seatGrid = new GridPane();

        seatGrid.setHgap(15);
        seatGrid.setVgap(15);
        seatGrid.setAlignment(Pos.CENTER);

        seatGrid.add(
                new Label("Loading seats..."),
                0,
                0
        );

        selectedSeatLabel =
                new Label("Selected Seat: None");

        bookingStatusLabel = new Label("");

        TextField nameField = new TextField();

        nameField.setPromptText(
                "Enter your full name"
        );

        nameField.setMaxWidth(260);

        bookButton =
                new Button("Book Selected Seat");

        bookButton.setDisable(
                !canBook()
        );

        bookButton.setOnAction(action -> {

            if (bookingPending) {
                return;
            }

            if (!canBook()) {

                bookingStatusLabel.setText(
                        "Please select a seat first."
                );

                return;
            }

            String customerName =
                    nameField.getText().trim();

            if (customerName.isEmpty() ||
                    customerName.length() > 100 ||
                    customerName.contains(":")) {

                bookingStatusLabel.setText(
                        "Enter a valid customer name."
                );

                return;
            }

            if (!isConnected()) {

                bookingStatusLabel.setText(
                        "Server is not connected."
                );

                return;
            }

            bookingPending = true;
            bookButton.setDisable(true);

            pendingCustomerName = customerName;
            pendingEvent = selectedEvent;

            bookingStatusLabel.setText(
                    "Booking " + selectedSeat + "..."
            );

            boolean sent = serverConnection.send(
                    "BOOK:" +
                            selectedEvent.id + ":" +
                            selectedSeat + ":" +
                            customerName
            );

            if (!sent) {

                bookingPending = false;
                bookButton.setDisable(true);

                bookingStatusLabel.setText(
                        "Request not sent. Please reconnect."
                );
            }
        });

        Button back =
                new Button("Back to Events");

        back.setOnAction(action -> {
            showEventsListScreen();
        });

        VBox layout = new VBox(
                15,
                createConnectionBar(),
                title,
                venue,
                date,
                stageLabel,
                seatGrid,
                new Label(
                        "Green = Available     Yellow = Held     Red = Booked"
                ),
                selectedSeatLabel,
                new Label("Customer Name:"),
                nameField,
                bookingStatusLabel,
                bookButton,
                back
        );

        layout.setAlignment(Pos.CENTER);
        layout.setPadding(new Insets(25));

        stage.setScene(
                new Scene(layout, 720, 680)
        );

        if (isConnected()) {

            serverConnection.send(
                    "GET_SEATS:" + event.id
            );

        } else {

            bookingStatusLabel.setText(
                    "Server is not connected."
            );
        }
    }

    // -----------------------------------------
    // DISPLAY SEATS
    // -----------------------------------------

    private void displaySeats(
            String eventId,
            String data
    ) {

        if (!"SEATS".equals(currentScreen) ||
                selectedEvent == null ||
                !eventId.equals(selectedEvent.id)) {
            return;
        }

        seatGrid.getChildren().clear();
        seatButtons.clear();

        int index = 0;

        for (String row : data.split(",")) {

            if (row.isBlank()) {
                continue;
            }

            String[] parts =
                    row.split("=", 2);

            if (parts.length != 2) {
                continue;
            }

            String seatNumber = parts[0];

            Button button =
                    createSeatButton(seatNumber);

            seatGrid.add(
                    button,
                    index % 4,
                    index / 4
            );

            updateSeat(
                    seatNumber,
                    parts[1]
            );

            index++;
        }

        if (bookButton != null) {

            bookButton.setDisable(
                    !canBook()
            );
        }

        if (bookingStatusLabel != null &&
                !bookingPending) {

            bookingStatusLabel.setText(
                    index == 0
                            ? "No seats for this event."
                            : "Seats are up to date."
            );
        }

        if (index == 0) {

            seatGrid.add(
                    new Label(
                            "No seats available for this event."
                    ),
                    0,
                    0
            );
        }
    }

    // -----------------------------------------
    // CREATE SEAT BUTTON
    // -----------------------------------------

    private Button createSeatButton(
            String seatNumber
    ) {

        Button button =
                new Button(seatNumber);

        button.setPrefSize(70, 50);

        seatButtons.put(seatNumber, button);

        setSeatAvailable(button);

        button.setOnAction(action -> {

            if (bookingPending || !isConnected()) return;
            if (selectedSeat != null) serverConnection.send("RELEASE:" + selectedEvent.id + ":" + selectedSeat);
            clearHold("Requesting hold: " + seatNumber);
            selectedSeat = seatNumber;
            desiredHold = selectedEvent.id + ":" + seatNumber;
            holdRequests.add(desiredHold);
            bookButton.setDisable(true);
            selectedSeatLabel.setText("Requesting hold: " + seatNumber);
            serverConnection.send("HOLD:" + desiredHold);

        });

        return button;
    }

    // -----------------------------------------
    // AVAILABLE SEAT STYLE
    // -----------------------------------------

    private void setSeatAvailable(Button button) {

        button.setDisable(false);

        button.setStyle(
                "-fx-background-color: lightgreen;" +
                        "-fx-font-weight: bold;"
        );
    }

    // -----------------------------------------
    // UPDATE ONE SEAT
    // -----------------------------------------

    private void updateSeat(String seatNumber, String status) {
        Button button = seatButtons.get(seatNumber);
        if (button == null) return;
        boolean owned = seatNumber.equals(selectedSeat) && holdOwned;
        button.setStyle("-fx-background-color: " + ("BOOKED".equals(status) ? "lightcoral"
                : "HELD".equals(status) ? "gold" : "lightgreen") + ";-fx-font-weight: bold;");
        button.setDisable(!isConnected() || !"AVAILABLE".equals(status));
        if (owned && !"HELD".equals(status)) {
            clearHold("BOOKED".equals(status) ? "Seat is booked."
                    : System.currentTimeMillis() >= holdExpiry
                    ? "Hold expired. Select an available seat again."
                    : "Hold released. Select an available seat again.");
        }
        if (bookButton != null) bookButton.setDisable(!canBook());
    }

    // -----------------------------------------
    // FIND MY BOOKING SCREEN
    // -----------------------------------------

    private void showBookingLookupScreen() {
        leaveSeats();

        currentScreen = "LOOKUP";
        selectedEvent = null;
        seatButtons.clear();

        verifiedBookingId = "";
        verifiedCustomerName = "";

        Label title =
                new Label("Find My Booking");

        title.setStyle(
                "-fx-font-size: 24px;" +
                        "-fx-font-weight: bold;"
        );

        TextField idField =
                new TextField();

        idField.setPromptText("Enter Booking ID");
        idField.setMaxWidth(250);

        TextField nameField =
                new TextField();

        nameField.setPromptText(
                "Enter Customer Name"
        );

        nameField.setMaxWidth(250);

        bookingLookupResultArea =
                new TextArea();

        bookingLookupResultArea.setEditable(false);
        bookingLookupResultArea.setWrapText(true);
        bookingLookupResultArea.setMaxWidth(440);
        bookingLookupResultArea.setPrefRowCount(9);

        cancelButton =
                new Button("Cancel Booking");

        cancelButton.setDisable(true);

        cancelButton.setStyle(
                "-fx-background-color: lightcoral;" +
                        "-fx-font-weight: bold;"
        );

        // FIND BUTTON
        Button findButton =
                new Button("Find Booking");

        findButton.setOnAction(action -> {

            String bookingId =
                    idField.getText().trim();

            String name =
                    nameField.getText().trim();

            if (!validBookingInput(bookingId, name)) {

                bookingLookupResultArea.setText(
                        "Please enter a valid Booking ID " +
                                "and Customer Name."
                );

                return;
            }

            if (!isConnected()) {

                bookingLookupResultArea.setText(
                        "Server is not connected."
                );

                return;
            }

            cancelButton.setDisable(true);
            verifiedBookingId = "";

            requestedBookingId = bookingId;
            requestedCustomerName = name;

            bookingLookupResultArea.setText(
                    "Searching for booking..."
            );

            serverConnection.send(
                    "GET_BOOKING:" +
                            bookingId + ":" +
                            name
            );
        });

        // CANCEL BUTTON
        cancelButton.setOnAction(action -> {

            String bookingId =
                    idField.getText().trim();

            String name =
                    nameField.getText().trim();

            if (!bookingId.equals(verifiedBookingId) ||
                    !name.equals(verifiedCustomerName)) {

                bookingLookupResultArea.setText(
                        "Find this booking again before cancelling."
                );

                cancelButton.setDisable(true);
                return;
            }

            if (!isConnected()) {

                bookingLookupResultArea.setText(
                        "Server is not connected."
                );

                return;
            }

            Alert confirm = new Alert(
                    Alert.AlertType.CONFIRMATION
            );

            confirm.setTitle("Cancel Booking");

            confirm.setHeaderText(
                    "Are you sure you want to cancel booking #" +
                            bookingId + "?"
            );

            boolean accepted =
                    confirm.showAndWait()
                            .filter(ButtonType.OK::equals)
                            .isPresent();

            if (!accepted) {
                return;
            }

            cancelButton.setDisable(true);

            bookingLookupResultArea.setText(
                    "Cancelling booking..."
            );

            serverConnection.send(
                    "CANCEL_BOOKING:" +
                            bookingId + ":" +
                            name
            );
        });

        Button back =
                new Button("Back to Home");

        back.setOnAction(action -> {
            showHomeScreen();
        });

        VBox layout = new VBox(
                14,
                createConnectionBar(),
                title,
                new Label("Booking ID:"),
                idField,
                new Label("Customer Name:"),
                nameField,
                findButton,
                cancelButton,
                bookingLookupResultArea,
                back
        );

        layout.setAlignment(Pos.CENTER);
        layout.setPadding(new Insets(25));

        stage.setScene(
                new Scene(layout, 720, 620)
        );
    }

    // -----------------------------------------
    // VALIDATE BOOKING INPUT
    // -----------------------------------------

    private boolean validBookingInput(
            String bookingId,
            String customerName
    ) {

        return bookingId.matches("[1-9][0-9]*") &&
                !customerName.isBlank() &&
                customerName.length() <= 100 &&
                !customerName.contains(":");
    }

    // -----------------------------------------
    // HANDLE SERVER MESSAGES
    // -----------------------------------------

    private void handleServerMessage(String message) {

        System.out.println("Server: " + message);

        // CONNECTION LOST
        if (message.startsWith("CONNECTION_ERROR:")) {

            if (!isConnected()) {
                onConnectionLost();
            }

            return;
        }

        if (message.startsWith("HOLD_SUCCESS:") || message.startsWith("HOLD_DENIED:")) {
            String request = holdRequests.poll();
            String[] parts = message.split(":", 4);
            if (parts.length != 4 || request == null) return;
            // TCP replies are ordered. Only the last outstanding selection may become owned.
            if (!holdRequests.isEmpty() || !request.equals(desiredHold)
                    || !"SEATS".equals(currentScreen)) return;
            if (message.startsWith("HOLD_SUCCESS:")) {
                holdOwned = true;
                holdExpiry = Long.parseLong(parts[3]);
                updateSeat(parts[2], "HELD");
                holdClock.play();
                bookingStatusLabel.setText("Seat held for you. Confirm before the timer expires.");
                tickHold();
            } else {
                clearHold("Hold denied: " + parts[3]);
            }
            return;
        }

        if (message.startsWith("RELEASE_SUCCESS:")) {
            String[] parts = message.split(":", 3);
            if (parts.length == 3 && "SEATS".equals(currentScreen) && selectedEvent != null
                    && selectedEvent.id.equals(parts[1]) && parts[2].equals(selectedSeat) && holdOwned) {
                clearHold("Hold released. Select an available seat again.");
            }
            return;
        }

        // EVENTS
        if (message.startsWith("EVENTS:")) {

            displayEvents(
                    message.substring("EVENTS:".length())
            );

        }

        // SEATS
        else if (message.startsWith("SEATS:")) {

            String[] parts =
                    message.split(":", 3);

            if (parts.length == 3) {

                displaySeats(
                        parts[1],
                        parts[2]
                );
            }

        }

        // REAL-TIME UPDATE
        else if (message.startsWith("SEAT_UPDATE:")) {

            String[] parts =
                    message.split(":", 4);

            if (parts.length == 4 &&
                    "SEATS".equals(currentScreen) &&
                    selectedEvent != null &&
                    selectedEvent.id.equals(parts[1])) {

                updateSeat(
                        parts[2],
                        parts[3]
                );
            }

        }

        // BOOKING SUCCESS
        else if (message.startsWith("BOOKING_SUCCESS:")) {

            BookingSuccess booking;
            try {
                booking = parseBookingSuccess(message);
            } catch (IllegalArgumentException e) {
                bookingPending = false;
                clearHold("Invalid booking confirmation. Check Find My Booking before retrying.");
                return;
            }

            bookingPending = false;

            if (bookButton != null) {
                bookButton.setDisable(!canBook());
            }

            if ("SEATS".equals(currentScreen) &&
                    selectedEvent != null &&
                    selectedEvent.id.equals(booking.eventId())) {

                updateSeat(booking.seatNumber(), "BOOKED");

                bookingStatusLabel.setText(
                        "Booking successful: " + booking.seatNumber()
                );
            }

            showBookingConfirmation(
                    booking.bookingId(),
                    booking.bookingTime(),
                    booking.seatNumber(),
                    pendingCustomerName,
                    pendingEvent
            );
        }

        // ALREADY BOOKED
        else if (message.startsWith("ALREADY_BOOKED:")) {

            bookingPending = false;

            if (bookButton != null) {
                bookButton.setDisable(!canBook());
            }

            String[] parts =
                    message.split(":", 3);

            if (parts.length == 3 &&
                    "SEATS".equals(currentScreen) &&
                    selectedEvent != null &&
                    selectedEvent.id.equals(parts[1])) {

                updateSeat(parts[2], "BOOKED");

                bookingStatusLabel.setText(
                        "Seat " + parts[2] +
                                " is already booked."
                );
            }

        }

        // BOOKING FAILED
        else if (message.startsWith("BOOKING_FAILED:")) {

            bookingPending = false;

            if (bookButton != null) {
                bookButton.setDisable(!canBook());
            }

            if ("SEATS".equals(currentScreen) &&
                    bookingStatusLabel != null) {

                bookingStatusLabel.setText(
                        "Booking failed. Please refresh the seat list."
                );
            }

        }

        // CANCELLATION SUCCESS
        else if (message.startsWith("CANCEL_SUCCESS:")) {

            String[] parts =
                    message.split(":", 4);

            if (parts.length == 4) {

                if ("LOOKUP".equals(currentScreen) &&
                        bookingLookupResultArea != null) {

                    bookingLookupResultArea.setText(
                            "BOOKING CANCELLED SUCCESSFULLY!\n\n" +
                                    "Booking ID: #" + parts[1] + "\n" +
                                    "Event ID: " + parts[2] + "\n" +
                                    "Seat: " + parts[3] + "\n" +
                                    "Status: CANCELLED"
                    );

                    cancelButton.setDisable(true);
                    verifiedBookingId = "";
                }

                if ("SEATS".equals(currentScreen) &&
                        selectedEvent != null &&
                        selectedEvent.id.equals(parts[2])) {

                    updateSeat(parts[3], "AVAILABLE");
                }
            }

        }

        // ALREADY CANCELLED
        else if (message.startsWith("ALREADY_CANCELLED:")) {

            if ("LOOKUP".equals(currentScreen) &&
                    bookingLookupResultArea != null) {

                bookingLookupResultArea.setText(
                        "This booking is already cancelled."
                );

                cancelButton.setDisable(true);
            }

        }

        // BOOKING DETAILS
        else if (message.startsWith("BOOKING_DETAILS:")) {

            String[] parts =
                    message.split(":", 9);

            if (parts.length == 9 &&
                    "LOOKUP".equals(currentScreen) &&
                    bookingLookupResultArea != null &&
                    parts[1].equals(requestedBookingId)) {

                bookingLookupResultArea.setText(
                        "BOOKING FOUND\n\n" +
                                "Booking ID: #" + parts[1] + "\n" +
                                "Seat: " + parts[2] + "\n" +
                                "Customer: " + parts[3] + "\n" +
                                "Event: " + parts[4] + "\n" +
                                "Venue: " + parts[5] + "\n" +
                                "Event Date: " + parts[6] + "\n" +
                                "Status: " + parts[7] + "\n" +
                                "Booking Time: " + parts[8]
                );

                verifiedBookingId = parts[1];

                verifiedCustomerName =
                        requestedCustomerName;

                cancelButton.setDisable(
                        !"ACTIVE".equalsIgnoreCase(parts[7])
                );
            }

        }

        // BOOKING LOOKUP ERRORS
        else if (message.equals("BOOKING_NOT_FOUND") ||
                message.equals("INVALID_BOOKING_REQUEST") ||
                message.equals("INVALID_CANCEL_REQUEST") ||
                message.equals("CANCEL_FAILED")) {

            if ("LOOKUP".equals(currentScreen) &&
                    bookingLookupResultArea != null) {

                bookingLookupResultArea.setText(
                        message.replace('_', ' ')
                );

                cancelButton.setDisable(true);
            }

        }

        // GENERAL ERRORS
        else if (message.equals("DATABASE_ERROR") ||
                message.equals("SEAT_NOT_FOUND") ||
                message.equals("EVENT_NOT_FOUND") ||
                message.equals("INVALID_CUSTOMER_NAME") ||
                message.equals("UNKNOWN_COMMAND")) {

            bookingPending = false;

            if (bookButton != null) {
                bookButton.setDisable(!canBook());
            }

            if ("LOOKUP".equals(currentScreen) &&
                    bookingLookupResultArea != null) {

                bookingLookupResultArea.setText(
                        message.replace('_', ' ')
                );

            } else if ("SEATS".equals(currentScreen) &&
                    bookingStatusLabel != null) {

                bookingStatusLabel.setText(
                        message.replace('_', ' ')
                );

            } else if ("EVENTS".equals(currentScreen) &&
                    eventsContainer != null) {

                eventsContainer.getChildren().setAll(
                        new Label(
                                message.replace('_', ' ')
                        )
                );
            }
        }
    }

    // -----------------------------------------
    // BOOKING CONFIRMATION
    // -----------------------------------------

    record BookingSuccess(String eventId, String seatNumber, String bookingId, String bookingTime) { }

    static BookingSuccess parseBookingSuccess(String message) {
        // Limit the split: the final timestamp itself contains colons.
        // Older responses without a timestamp still contain four fields.
        String[] parts = message.split(":", 5);
        if (parts.length < 4 || !"BOOKING_SUCCESS".equals(parts[0])
                || parts[1].isBlank() || parts[2].isBlank() || !parts[3].matches("[1-9][0-9]*")) {
            throw new IllegalArgumentException("Malformed booking confirmation");
        }
        return new BookingSuccess(parts[1], parts[2], parts[3], parts.length == 5 ? parts[4] : "");
    }

    private void showBookingConfirmation(
            String bookingId,
            String bookingTime,
            String seatNumber,
            String customerName,
            EventInfo event
    ) {

        Alert alert = new Alert(
                Alert.AlertType.INFORMATION
        );

        alert.setTitle(
                "EventSeat - Booking Confirmation"
        );

        alert.setHeaderText(
                "Booking Confirmed Successfully!"
        );

        alert.setContentText(
                "Booking ID: #" + bookingId + "\n" +
                        (bookingTime.isBlank() ? "" : "Booking Time: " + bookingTime + "\n") + "\n" +
                        "Customer: " + customerName + "\n" +
                        "Event: " +
                        (event == null ? "Unknown" : event.name) + "\n" +
                        "Venue: " +
                        (event == null ? "Unknown" : event.venue) + "\n" +
                        "Date: " +
                        (event == null ? "Unknown" : event.date) + "\n" +
                        "Seat: " + seatNumber + "\n\n" +
                        "Thank you for booking with EventSeat!"
        );

        alert.showAndWait();
    }

    // -----------------------------------------
    // MAIN METHOD
    // -----------------------------------------

    public static void main(String[] args) {

        launch(args);
    }
}
