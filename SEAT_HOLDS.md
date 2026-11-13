# Temporary seat holds

Each connection can own one temporary hold. Selecting another seat releases the
previous hold; a denied replacement leaves the previous hold intact unless the
client explicitly released it. Holds are memory-only and last 60 seconds.

## Protocol

Commands are newline-delimited TCP messages on port 5000:

| Command | Response |
| --- | --- |
| `HOLD:E001:A2` | `HOLD_SUCCESS:E001:A2:<Unix epoch milliseconds>` |
| conflicting hold | `HOLD_DENIED:E001:A2:HELD` |
| booked seat | `HOLD_DENIED:E001:A2:BOOKED` |
| invalid event/seat | `HOLD_DENIED:E001:A2:EVENT_NOT_FOUND` / `SEAT_NOT_FOUND` |
| `RELEASE:E001:A2` | `RELEASE_SUCCESS:E001:A2` (idempotent when unheld) |
| another owner's release | `RELEASE_DENIED:E001:A2` |
| `LEAVE_SEATS` | `LEAVE_SUCCESS` |
| malformed hold/release | `INVALID_HOLD_REQUEST` |

`GET_SEATS:E001` subscribes to that event and returns
`SEATS:E001:A1=AVAILABLE,A2=HELD,A3=BOOKED,...`. Other subscribers receive
`SEAT_UPDATE:E001:A2:HELD`, `AVAILABLE`, or `BOOKED`. Snapshot and update ordering
is protected by the shared EventManager monitor. Ownership is never included in
snapshots: the GUI recognizes ownership only from its own HOLD_SUCCESS reply.
Repeated HOLD by the same owner returns the original expiry without extending it.

BOOK retains both existing formats. Unheld seats can still be booked directly;
another connection's held seat returns `BOOKING_FAILED:HELD`. The owner's booking
uses the existing MySQL transaction. Success removes the timer without an
intermediate AVAILABLE update. Database failures retain the hold until expiry.
EXIT, disconnect, LEAVE_SEATS, GET_EVENTS, and switching event subscriptions
release the connection's hold.

## Concurrency and UI

EventManager's monitor serializes holds, bookings, cancellation, snapshots, and
expiry. One scheduled executor handles timers. Each task checks the exact hold
object before removal, so a cancelled stale task cannot remove a replacement.
Timers are cancelled and removed on release/booking, and shutdown closes the
scheduler. Per-client bounded outbound queues keep slow sockets outside the
shared monitor; a full queue closes that client. Ordered replies and broadcasts
share the same writer.

JavaFX uses green/yellow/red status colors. Only a confirmed, unexpired owned hold
enables booking. A Timeline displays remaining seconds. Pending hold replies are
matched in TCP order and ignored after navigation or a newer selection. Network
callbacks enter through Platform.runLater, and an ordered sender thread performs
socket writes. Reconnection discards ownership and requests a fresh snapshot.
The countdown uses the server epoch timestamp; keep client/server clocks aligned.
The server is authoritative even if a client clock differs.

## PowerShell validation

From the repository directory, with Maven on PATH:

```powershell
mvn clean compile
mvn test
java -cp target/classes com.eventseat.server.SeatHoldTestMain --embedded
```

On this workstation Maven was found outside PATH. Equivalent setup:

```powershell
$mvn = 'C:\Users\MSI\.m2\wrapper\dists\apache-maven-3.9.9\bin\mvn.cmd'
& $mvn '-Dmaven.repo.local=C:/Users/MSI/.m2/repository' clean compile
& $mvn '-Dmaven.repo.local=C:/Users/MSI/.m2/repository' test
java -cp target/classes com.eventseat.server.SeatHoldTestMain --embedded
```

The embedded runner starts real TCP handlers on a loopback ephemeral port with
isolated E001/E002 events. It does not need MySQL or create bookings. Allow about
60 seconds. Expected output includes `PASS:` for each assertion and finishes with:

```text
ALL SEAT HOLD TCP TESTS PASSED (no bookings created)
```

`mvn test` succeeds but reports `No tests to run`: this project uses runnable main
classes rather than a unit-test framework. Run the TCP main explicitly.

For the real MySQL server, first configure your existing private database setup
yourself, then start ServerMain with the dependency classpath:

```powershell
mvn dependency:build-classpath '-Dmdep.outputFile=target/classpath.txt'
$cp = 'target/classes;' + (Get-Content target/classpath.txt -Raw).Trim()
java -cp $cp com.eventseat.server.ServerMain
```

In another PowerShell window:

```powershell
java -cp target/classes com.eventseat.server.SeatHoldTestMain localhost 5000
```

The external test requires E001 and E002 with at least one same-named available
seat, selected dynamically. Use a quiet test database/event: competing users can
invalidate assertions. The test never intentionally books a seat and closes all
connections to release outstanding holds.

Launch two GUI clients in separate terminals with `mvn javafx:run`. Verify yellow
updates, disabled booking for the other client, owner countdown, expiry, changing
seats, navigation, EXIT/disconnect, and reconnection. Confirm an owner booking and
legacy direct booking against a disposable database, then use the existing
cancellation screen to clean up those bookings. Embedded tests do not validate
MySQL commits, owner booking success, or visual JavaFX interaction.

## Validation performed

Maven clean compile and test completed successfully using JDK 27 and the existing
Java 25 source/target settings. Maven reports no framework tests. The embedded
TCP runner passed the required hold, contention, release, real 60-second expiry,
disconnect, and cross-event checks. No external server was listening on port 5000;
the MySQL-backed and GUI manual checks above remain to be run. The private
database properties file was not opened or changed.
