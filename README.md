# 🎟️ EventSeat – Real-Time Event Seat Booking System

**EventSeat** is a Java-based network application designed to provide real-time seat booking for concerts and events.

The system uses TCP Socket Programming to establish communication between multiple clients and a central server. When a client successfully books a seat, the server updates the MySQL database and broadcasts the seat status to all connected clients.

The project demonstrates practical networking concepts, multithreading, real-time communication, database integration, and JavaFX GUI development.

---

## 📌 Project Overview

EventSeat allows multiple users to connect to a central server and book seats for different events.

The server manages client connections, processes booking requests, prevents double bookings, and synchronizes seat availability across connected clients.

The application is developed using Java, JavaFX, TCP Socket Programming, Maven, and MySQL.

## ✨ Implemented Features

The following features have been implemented:

### 🌐 Networking Features
- TCP Socket Programming for client-server communication.
- Centralized server architecture.
- Multiple client connections.
- Multithreading to handle concurrent clients.
- Client-server message exchange.
- Real-time seat status broadcasting.

### 🎫 Seat Booking Features
- Interactive seat selection.
- Available and booked seat identification.
- Double-booking prevention.
- Real-time seat synchronization between clients.
- Booking requests handled by the server.
- Persistent seat booking using MySQL.

### 🖥️ Graphical User Interface
- JavaFX desktop application.
- Event information display.
- Interactive seat layout.
- Seat selection and booking buttons.
- Booking status messages.
- Visual indication of available and booked seats.

### 🗄️ Database Features
- MySQL database integration.
- JDBC connectivity.
- Event information retrieval.
- Seat information retrieval.
- Booking information storage.
- Database transaction handling.
- Persistent booking records.
- Booking information retained after server restart.

---

## 🛠️ Technologies Used

| Technology | Purpose |
|---|---|
| Java 25 | Core programming language |
| JavaFX | Desktop GUI |
| TCP Socket Programming | Network communication |
| Multithreading | Concurrent client handling |
| MySQL | Database management |
| JDBC | Database connectivity |
| Maven | Dependency management |
| Git | Version control |
| GitHub | Source code hosting |
| Visual Studio Code / IntelliJ IDEA | Development environments |

---

## 🏗️ System Architecture

```text
                  MySQL Database
                        |
                        |
                 Java TCP Server
                        |
                  Socket Server
                        |
                  Multithreading
                        |
              Multiple Client Connections
                        |
          +-------------+-------------+
          |             |             |
       Client 1      Client 2      Client 3
          |             |             |
        JavaFX        JavaFX        JavaFX
          |             |             |
       Seat UI       Seat UI       Seat UI
```

The central server processes booking requests and broadcasts seat availability updates to all connected clients.

---

## 📂 Project Structure

```text
EventSeat/
│
├── src/
│   └── main/
│       ├── java/
│       │   └── com/
│       │       └── eventseat/
│       │           │
│       │           ├── client/
│       │           │   ├── EventSeatApp.java
│       │           │   └── ServerConnection.java
│       │           │
│       │           ├── model/
│       │           │   ├── Event.java
│       │           │   └── Seat.java
│       │           │
│       │           ├── repository/
│       │           │   ├── EventRepository.java
│       │           │   └── BookingRepository.java
│       │           │
│       │           ├── server/
│       │           │   ├── ServerMain.java
│       │           │   ├── ClientHandler.java
│       │           │   └── EventManager.java
│       │           │
│       │           └── util/
│       │               ├── DatabaseConnection.java
│       │               └── DatabaseTestMain.java
│       │
│       └── resources/
│           └── db.properties (local configuration)
│
├── .gitignore
├── pom.xml
└── README.md
```

---

## 🗄️ Database Design

The project uses a MySQL database named `eventseat_db`.

The database contains three main tables:

### 1. Events Table

Stores event information.

| Column | Description |
|---|---|
| event_id | Unique event ID |
| event_name | Event name |
| venue | Event location |
| event_date | Event date |

### 2. Seats Table

Stores seat information.

| Column | Description |
|---|---|
| seat_id | Unique seat ID |
| event_id | Related event |
| seat_number | Seat number |
| status | AVAILABLE / BOOKED |

### 3. Bookings Table

Stores customer booking records.

| Column | Description |
|---|---|
| booking_id | Unique booking ID |
| event_id | Related event |
| seat_number | Booked seat |
| customer_name | Customer name |
| booking_time | Booking timestamp |

---

## ⚙️ Installation and Setup

### Prerequisites

Make sure you have installed:

- JDK 25
- Apache Maven
- MySQL Server
- MySQL Workbench
- Visual Studio Code or IntelliJ IDEA
- Java Extension Pack (for VS Code)

### Step 1 – Clone the Repository

```bash
git clone YOUR_GITHUB_REPOSITORY_URL
```

Navigate to the project directory:

```bash
cd EventSeat
```

### Step 2 – Create MySQL Database

Open MySQL Workbench and execute:

```sql
CREATE DATABASE eventseat_db;

USE eventseat_db;
```

Create the required tables:

```sql
CREATE TABLE events (
    event_id VARCHAR(20) PRIMARY KEY,
    event_name VARCHAR(100) NOT NULL,
    venue VARCHAR(100) NOT NULL,
    event_date DATE NOT NULL
);

CREATE TABLE seats (
    seat_id INT AUTO_INCREMENT PRIMARY KEY,
    event_id VARCHAR(20) NOT NULL,
    seat_number VARCHAR(10) NOT NULL,
    status VARCHAR(20) NOT NULL DEFAULT 'AVAILABLE',
    FOREIGN KEY (event_id) REFERENCES events(event_id),
    UNIQUE (event_id, seat_number)
);

CREATE TABLE bookings (
    booking_id INT AUTO_INCREMENT PRIMARY KEY,
    event_id VARCHAR(20) NOT NULL,
    seat_number VARCHAR(10) NOT NULL,
    customer_name VARCHAR(100),
    booking_time TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    FOREIGN KEY (event_id) REFERENCES events(event_id)
);
```

Insert event and seat records before running the application.

### Step 3 – Configure Database Connection

Create a local configuration file:

`src/main/resources/db.properties`

Add your MySQL credentials:

```properties
db.url=jdbc:mysql://localhost:3306/eventseat_db
db.username=root
db.password=YOUR_MYSQL_PASSWORD
```

**Important:** Never upload your actual `db.properties` file or database password to GitHub.

The file is excluded using `.gitignore`.

### Step 4 – Compile the Project

Open a terminal in the project directory:

```bash
mvn clean compile
```

### Step 5 – Start the Server

Open `ServerMain.java` in your IDE and run its main method.

The server listens on port `5000`.

### Step 6 – Start the JavaFX Client

Open another terminal and execute:

```bash
mvn javafx:run
```

The EventSeat JavaFX application will launch and connect to the server.

---

## 🔄 How the Booking System Works

1. A client connects to the TCP server.
2. The client requests the latest seat availability.
3. The server retrieves the seat information.
4. The user selects an available seat.
5. The client sends a booking request.
6. The server checks seat availability.
7. MySQL updates the seat status.
8. A booking record is saved.
9. The server broadcasts the updated seat status.
10. All connected clients receive the update.

### Real-Time Booking Example

```text
Client 1                 Server                Client 2
   |                        |                      |
   |------ BOOK:A2 -------->|                      |
   |                        |                      |
   |                        |---- Update MySQL     |
   |                        |                      |
   |<--- BOOKING_SUCCESS ---|                      |
   |                        |                      |
   |                        |--- SEAT_UPDATE ----->|
   |                        |                      |
   |     A2 BOOKED          |       A2 BOOKED      |
```

This process helps prevent double bookings and keeps connected clients synchronized.

---

## 🚀 Future Development Roadmap

The following features are planned for upcoming development phases.

### Step 18 – Multiple Events

- [x] Load multiple events from MySQL.
- [ ] Display multiple events in the JavaFX interface.
- [ ] Allow users to select their preferred event.
- [ ] Display seats belonging to the selected event.

### Step 19 – Customer Details & Booking Confirmation

- [ ] Add customer name input.
- [ ] Generate a unique Booking ID after successful booking.
- [ ] Save booking time.
- [ ] Display booking confirmation details.

### Step 20 – Find My Booking

- [ ] Search bookings using Booking ID.
- [ ] Display customer name.
- [ ] Display event information.
- [ ] Display booked seat number.
- [ ] Display booking time and status.

### Step 21 – Booking Cancellation

- [ ] Allow customers to cancel bookings.
- [ ] Update booking status to CANCELLED in MySQL.
- [ ] Make cancelled seats AVAILABLE again.
- [ ] Broadcast seat availability updates to other clients.

### Step 22 – Connection Recovery UI

- [ ] Display a message when the server disconnects.
- [ ] Add a reconnect button.
- [ ] Re-establish the server connection.
- [ ] Restore the latest seat availability after reconnecting.

### Step 23 – Manual Testing Programs

- [ ] Test network commands.
- [ ] Test server error responses.
- [ ] Test 10 simultaneous client connections.
- [ ] Perform concurrent booking tests.
- [ ] Test multiple clients attempting to book the same seat.
- [ ] Verify that double booking is prevented under concurrent requests.

---

## 🧪 Testing

Implemented functionalities have been tested through:

- Client-server communication.
- Multiple client connections.
- Real-time seat synchronization.
- Seat booking operations.
- Double-booking prevention.
- MySQL database connectivity.
- Booking persistence after server restart.

Additional testing activities are included in the development roadmap.

---

## 🎯 Project Objectives

The main objectives of EventSeat are:

- To understand TCP Socket Programming.
- To implement a client-server architecture.
- To demonstrate multithreading and concurrent client handling.
- To implement real-time communication.
- To prevent duplicate seat bookings.
- To integrate a Java application with MySQL.
- To demonstrate database transactions and data persistence.

---

## 👨‍💻 Academic Project

EventSeat is developed as an academic networking project to demonstrate practical networking concepts using Java.

**EventSeat – Making Event Booking Simple, Secure, and Synchronized.** 🎟️
