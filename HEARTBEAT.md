# TCP heartbeat and live connection monitoring

EventSeat now monitors registered TCP sessions independently of event subscriptions.
The reusable JavaFX connection bar shows connection state, online clients, the latest
measured RTT, and Waiting for heartbeat / Monitoring / Connection lost. Before the
first valid response, latency is Measuring...; disconnected values are cleared.
This demonstrates TCP/application-transport liveness, **not JavaFX UI responsiveness**:
the background reader can answer while the JavaFX application thread is busy.

## Wire protocol

All messages use the existing newline-delimited socket on port 5000:

```text
server -> client  CLIENT_STATS:1
server -> client  PING:<unique-request-id>
client -> server  PONG:<exact-same-request-id>
server -> client  LATENCY:<non-negative-integer-milliseconds>
```

Each connection has at most one pending challenge. IDs combine a manager UUID and
monotonic counter. Wrong, empty, whitespace-modified, cross-connection, duplicate,
expired and replayed PONGs are ignored. Only a matching, unexpired PONG satisfies
the challenge. Booking activity does not substitute for a heartbeat. Monitoring
frames are unsolicited and can occur between ordinary replies; raw clients must
answer PING and distinguish CLIENT_STATS/LATENCY from command replies.

## Scheduling and timeout

A single daemon ScheduledExecutorService per EventManager checks sessions at most
every 100 ms (faster with shorter test durations). The initial challenge is sent
on the next tick. After success, the next challenge is due in 5 seconds. A pending
challenge is never replaced; failure to answer it within 15 seconds closes only
that socket. Detection occurs on the first tick after the deadline; OS scheduling
can delay it. Ordinary EOF/EXIT/write failures also trigger cleanup.

Override defaults using positive millisecond JVM properties:

```powershell
java '-Deventseat.heartbeat.intervalMs=5000' '-Deventseat.heartbeat.timeoutMs=15000' -cp $cp com.eventseat.server.ServerMain
```

Tests inject Duration values (150 ms interval, 1200 ms timeout).
RTT is `(System.nanoTime() - sentNanoTime)` converted to milliseconds. It includes
outbound queue delay, transport and client processing, and server scheduling; it
is an application round trip, not pure wire latency. Zero milliseconds is valid
on loopback. The session retains its latest successful response time and RTT.

## Concurrency and cleanup

HeartbeatManager serializes registration, challenge validation, count broadcasts,
and timeout removal with its own monitor. Connection-object identity prevents an
old challenge from targeting a reconnected session. Counts are broadcast only when
membership changes, globally across event screens; a new session receives its
initial count immediately. A failed socket is removed at cleanup or the next tick.

The scheduler queues messages into the existing bounded 512-entry outbound queue;
only the handler's sender writes to its socket. Full queues fail closed. The
scheduler never waits for socket writes and never takes the EventManager monitor.
The handler remains the only socket reader. It validates PONG immediately without
waiting for database work or the EventManager lock. Ordinary commands enter a
bounded 512-entry FIFO queue serviced by one daemon command worker per connection;
that worker executes commands sequentially, preserving ownership checks and wire
reply ordering. Full command queues close the overloaded session rather than block
the reader. No extra reader or database connection is used for heartbeat handling.

Closing a timed-out socket wakes the reader, which unregisters the session once,
removes it from broadcasts, discards queued commands and interrupts the workers.
Only the command worker's terminal finally path calls EventManager.releaseOwner(this),
after any in-flight operation finishes. Commands waiting for the shared lock recheck
socket closure before changing holds, subscriptions, bookings or cancellations.
EXIT is processed in FIFO order, releases holds through the same terminal path,
then gives the existing writer a bounded opportunity to send Goodbye! before close.
Normal command-related releases (navigation or explicit RELEASE) retain their
existing meaning. Heartbeats never refresh the 60-second seat-hold deadline.
Reader cleanup uses bounded worker joins and clears the outbound queue.

EventManager.close (already called by ServerMain's shutdown hook/finally) shuts down
heartbeats, closes active sockets and stops the hold scheduler. Heartbeat shutdown
is idempotent and waits briefly for scheduler termination. A handler busy in an
existing database operation must finish that operation before terminal hold cleanup;
heartbeats do not cancel or roll back database transactions. A permanently blocked
JDBC call is not forcibly killed by Java thread interruption; its daemon command
worker and hold cleanup can remain pending until that operation/lock is released.
The socket reader and heartbeat validation remain independent throughout.

ServerConnection consumes PING inside its existing reader and sends PONG through
its existing single asynchronous sender, now bounded to 512 pending tasks. It uses
socket identity to discard stale sends and a connection generation to discard old
queued JavaFX callbacks. A generation identifies an installed TCP connection and
advances only when a new connection is installed, not on EOF or close. Responses
already queued on Platform.runLater therefore execute before that session's ordered
disconnect notification, even when EOF arrives before JavaFX dispatch. Replacing
the connection invalidates the old session's queued responses and notifications.
No retry or synthetic success is introduced. A received cancellation/lookup result
stays visible alongside the disconnect notice; a received booking confirmation is
shown by the existing confirmation dialog exactly once per received success frame.
Idle sender workers retire after one second. No extra reader or socket is created. ClientMain uses this same transport. JavaFX updates
enter through Platform.runLater; reconnect recovery and seat/booking disabling
remain in place, with lookup also disabled while disconnected.

## Files

Created:
- HEARTBEAT.md
- src/main/java/com/eventseat/server/HeartbeatManager.java
- src/main/java/com/eventseat/server/HeartbeatTestMain.java
- src/main/java/com/eventseat/server/TcpTestClient.java

Modified:
- src/main/java/com/eventseat/server/ClientHandler.java
- src/main/java/com/eventseat/server/EventManager.java
- src/main/java/com/eventseat/client/ServerConnection.java
- src/main/java/com/eventseat/client/EventSeatApp.java
- src/main/java/com/eventseat/client/ClientMain.java
- src/main/java/com/eventseat/server/SeatHoldTestMain.java
- src/main/java/com/eventseat/server/NetworkReliabilityTestMain.java
- src/main/java/com/eventseat/server/ConcurrentBookingStressTestMain.java
- src/test/java/com/eventseat/client/UiRegressionCheck.java

## PowerShell validation

Run from the repository root. This workstation's Maven is outside PATH:

```powershell
$mvn = 'C:\Users\MSI\.m2\wrapper\dists\apache-maven-3.9.9\bin\mvn.cmd'
& $mvn '-Dmaven.repo.local=C:/Users/MSI/.m2/repository' clean compile
& $mvn '-Dmaven.repo.local=C:/Users/MSI/.m2/repository' test
java -cp target/classes com.eventseat.server.HeartbeatTestMain
java -cp target/classes com.eventseat.server.SeatHoldTestMain --embedded
java -cp target/classes com.eventseat.server.NetworkReliabilityTestMain --embedded
$fx = (Get-ChildItem "$env:USERPROFILE\.m2\repository\org\openjfx" -Recurse -Filter '*27.0.1-win.jar' | ForEach-Object FullName) -join ';'
java --module-path $fx --add-modules javafx.controls --enable-native-access=javafx.graphics -cp 'target/classes;target/test-classes' com.eventseat.client.UiRegressionCheck
```

Expected final lines, alongside individual PASS assertions:

```text
BUILD SUCCESS
ALL HEARTBEAT TCP TESTS PASSED
ALL SEAT HOLD TCP TESTS PASSED (no bookings created)
ALL UI REGRESSION CHECKS PASSED
ALL NETWORK RELIABILITY TESTS PASSED!
```

Maven's test phase compiles standalone checks but does not discover framework tests;
the explicit java commands are required. TCP runners use ephemeral loopback ports
and in-memory events; no MySQL configuration or booking data is accessed. The hold
runner takes roughly 60 seconds and continuously responds to heartbeats while idle.
Heartbeat tests cover PING/PONG, RTT, wrong/cross-session/replayed responses, two
independent clients, counts, timeout and hold release, existing seat and booking
ownership replies, real ServerConnection automatic replies/reconnect, EXIT, active
socket shutdown and worker termination. JavaFX checks exercise network labels,
screen recreation, disconnected state, and previous hold/confirmation regressions.

The slow-operation regression holds the shared EventManager monitor using latches
while one client executes GET_EVENT and another waits for the lock. A third peer's
actual heartbeat timeout establishes that the operation remained blocked longer
than the timeout. Both healthy clients must still receive fresh RTT measurements,
retain their original holds/expiry, and receive subsequent command replies in FIFO
order. Queue saturation disconnects an overloaded fourth peer; queued holds from
closed sessions must not execute after the lock is released.

The JavaFX runner uses real loopback sockets and deliberately gates the FX queue
until BOOKING_SUCCESS or CANCEL_SUCCESS plus EOF has been received. It observes
the actual confirmation dialog/result, verifies exactly-once delivery and ordered
disconnect, and repeats with a replacement connection installed before FX dispatch
to verify stale callbacks are rejected. EOF without a success response creates no
confirmation. Tests close dialogs automatically. The existing network reliability
runner now has an --embedded mode that runs its ten cases without MySQL and exits
with failure if an assertion fails.

## Two-client demonstration

With your existing private MySQL configuration already set up, start the normal
server in one terminal (this demo uses your existing event data):

```powershell
& $mvn dependency:build-classpath '-Dmdep.outputFile=target/classpath.txt'
$cp = 'target/classes;' + (Get-Content target/classpath.txt -Raw).Trim()
java -cp $cp com.eventseat.server.ServerMain
```

In each of two separate terminals, set $mvn as above and run:

```powershell
& $mvn javafx:run
```

1. See Connected, online count 1 then 2, and Measuring... change to a measured RTT.
2. Browse different events: both clients retain the same global count.
3. Open the same event and hold a seat: the other client sees yellow HELD.
4. Close the holding client: the survivor sees count 1 and the seat available.
5. Stop/restart the server and click Reconnect: status clears then measurements resume.
6. Run HeartbeatTestMain to demonstrate an intentionally nonresponding peer timing
   out while its healthy peer survives (closing a GUI normally demonstrates EOF).

## Validation scope and limitations

Clean compilation, Maven test phase, embedded heartbeat TCP, full 60-second seat
hold TCP regression, ten embedded network reliability cases and standalone JavaFX
checks (including the new slow-operation and EOF/reconnect regressions) were executed
successfully with
the installed JDK 27 and unchanged Java 25 source/target settings. JDK 25 runtime
execution itself was not available for validation. Maven emits existing JDK warnings.
The user reported these completed manual checks before these reliability fixes:
- Two JavaFX clients connected simultaneously.
- Online Clients changed from 1 to 2.
- Both clients displayed measured RTT.
- Closing Client 2 updated Client 1's count to 1.
- Stopping the TCP server showed Disconnected and Connection lost.
- Reconnecting restored the TCP connection.

These manual steps were not repeated by the agent after the fixes. Production
database transaction tests and the MySQL-backed stress runner were not executed. The in-memory tests verify booking ownership
rejection/protocol compatibility, not successful SQL commits or cancellations.
Database repositories, schema, credentials, Maven configuration and .gitignore are
unchanged. No commit, push, branch switch or PR was performed.
