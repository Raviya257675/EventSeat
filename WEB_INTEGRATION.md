# EventSeat React / Java TCP integration

## Implemented architecture

```text
Browser React UI --HTTP + WebSocket--> Node gateway :8080
                                         |
                               one persistent TCP socket per browser
                                         |
JavaFX / portable EXE ---------> Java ServerMain :5000 ---> MySQL
```

Node 24 is installed on the development laptop. The gateway uses Node's built-in
HTTP/TCP modules and one additional runtime dependency, `ws`. It serves only
`web/dist`; it has no JDBC, MySQL credentials or access path to project resources.
ServerMain, EventManager, ClientHandler, repositories, the TCP protocol and the
existing JavaFX/LAN/portable sources are unchanged by this integration.

The React design retains the supplied styling, DM Sans fonts, icons, six images,
responsive event cards, search, sorting, seat map, digital ticket, QR reference,
lookup/cancellation dialog and printing. Event data is fetched from Java. Images
are labelled illustrative. Unsupported prices, categories, city fields, start
times and invented capacity are removed. Seat maps render actual server IDs;
their arrangement is schematic because the protocol provides no floor plan.
Bookings are single-seat and IDs are the numeric IDs returned by MySQL. There is
no localStorage booking database, simulated success or browser demo inventory.

## Start on the server laptop (Windows PowerShell)

Required: Windows JDK 27, Node.js 24 with npm, the existing configured MySQL
database and local `src/main/resources/db.properties`. Maven is optional: the
provided script compiles with JDK tools and resolves the JavaFX Windows and JDBC
JARs from the local Maven cache or Maven Central. Internet is needed on first
dependency installation. Client browser laptops need only a browser.

Open PowerShell in the project folder:

```powershell
Set-Location 'C:\Users\MSI\Documents\Group Project\EventSeat-Sadini'
Get-Service MySQL80
# Only if MySQL80 is stopped, in an elevated terminal:
Start-Service MySQL80
```

Use your installed MySQL service name if different. Keep the existing database;
do not recreate tables or overwrite credentials. This integration adds no schema
migration. Existing cancellation requires the existing `bookings.status` column
with ACTIVE/CANCELLED values, as used by the Java repositories.

Terminal 1 (skip if ServerMain is already listening on 5000):

```powershell
powershell -NoProfile -ExecutionPolicy Bypass -File .\scripts\Start-JavaServer.ps1
```

This invokes `Build-Java.ps1`, writes fresh classes under `target/web-java`, then
runs the existing ServerMain. Override `-JdkHome` if needed. Resources stay local;
the classpath refers to the resource directory rather than copying secrets.
The script does not delete previous build outputs. If Maven is installed, the
existing `mvn compile` plus dependency-classpath workflow in LAN_DEMO.md also works.

Terminal 2, first build and local launch:

```powershell
powershell -NoProfile -ExecutionPolicy Bypass -File .\scripts\Start-Web.ps1 -Build
```

Open **http://localhost:8080**. Later launches can omit `-Build`. Ctrl+C stops the
gateway. The `/health` endpoint reports gateway health only; the UI reports the
actual Java TCP connection separately. `npm run dev` is optional Vite development
mode and proxies `/ws` to the running gateway; use the gateway URL for the demo.

For other laptops, restart the gateway with LAN binding:

```powershell
powershell -NoProfile -ExecutionPolicy Bypass -File .\scripts\Start-Web.ps1 -Lan
ipconfig
```

LAN URL: **http://<server-laptop-IPv4>:8080** (for example
`http://192.168.1.10:8080`). The gateway connects only to `127.0.0.1:5000`.
Do not browse TCP port 5000. JavaFX clients continue to use TCP 5000 and
EVENTSEAT_SERVER_HOST as documented in LAN_DEMO.md and PORTABLE_CLIENT.md.
No firewall changes are required for localhost. LAN access can require allowing
TCP 8080 on the server's trusted Private network; JavaFX LAN needs TCP 5000.
Guest Wi-Fi, hotspot/client isolation and Windows Firewall can block clients.
No firewall rules were changed during implementation. Do not expose MySQL to clients.

## Session, ownership and error behavior

- One WebSocket owns one TCP session. Reload/close releases that session's holds.
  Dead browser connections are detected by WebSocket ping/pong. TCP PING challenges
  are answered immediately at the gateway, independently of slow booking requests.
- Requests are typed JSON operations: events, seats, hold, release, leave, book,
  lookup and cancel. The gateway validates fields/lengths/IDs, rejects protocol
  delimiters, and never accepts raw commands or a browser-selected TCP destination.
- Each session has a bounded FIFO and one outstanding Java operation. Unsolicited
  seat/count/latency messages do not consume the pending response. TCP fragments
  are reassembled by newline. A timeout terminates the session rather than allowing
  a late response to be mistaken for another request.
- Selecting a new seat releases the previous hold. The gateway requires a matching
  unexpired hold before accepting web booking. Java remains the final authority.
  Hold countdown uses remaining time calculated at the gateway (on the server
  laptop) and a monotonic browser clock. Holds are not automatically renewed.
- Navigation leaves the seat subscription; disconnect and expiry release holds.
  Submissions are locked until a server reply. Success appears only after Java
  confirms. Errors and unknown outcomes are shown; requests are never replayed
  automatically. If a connection fails during booking, use the received ID if
  available or ask the demonstrator to check the server database before retrying.
- Reconnect creates a fresh TCP session, clears stale availability/ownership and
  reloads events/seats. Old WebSocket callbacks cannot update the replacement.
- Online count is Java's CLIENT_STATS count (JavaFX plus browser TCP sessions).
  RTT is explicitly **gateway ↔ Java**, not browser-to-server latency. No fake
  latency is displayed. The QR encodes the booking reference, not personal data
  or an authentication token. Ticket/lookup status is the last confirmed response;
  look up again for current status. Refresh requires a new lookup with ID/name.

Security scope: trusted LAN demonstration, with same-origin WebSocket checks,
bounded payloads/queues, request limits, timeouts, CSP and a restricted static
root. Existing ID+customer-name lookup semantics are preserved; this is not an
authenticated public ticketing service. HTTPS, account authentication and public
deployment hardening are outside this integration.

## Build and automated checks

```powershell
# From the repository root (no deletion of target or portable output):
powershell -NoProfile -ExecutionPolicy Bypass -File .\scripts\Build-Java.ps1 -RunChecks
Set-Location web
npm.cmd ci --cache ..\target\npm-cache --no-fund --no-audit
npm.cmd run typecheck
npm.cmd test
npm.cmd run build
```

Build output: `web/dist/`. Submit `web` source/config/package-lock/public assets,
Java source, scripts and documentation. Exclude node_modules, all target/dist
outputs, .playwright-mcp, archives, local db.properties and environment secrets.
The root .gitignore covers these. Keep the complete Java source project for
submission; the portable EXE alone is not a source submission.

### Optional isolated MySQL integration checks

These use a uniquely named disposable schema, never production event/booking
rows. The configured local MySQL account needs CREATE/DROP DATABASE permission.
The fixture uses the real EventManager, ClientHandler and repositories. It
temporarily overrides the JDBC URL only inside the test JVM. Production classes
are not modified. Ports 5001 and 8081 must be free.

Terminal A, from repository root after Build-Java:

```powershell
$testCp = (Get-Content .\target\web-java\classpath.txt -Raw).Trim()
& 'C:\Program Files\Java\jdk-27\bin\java.exe' -cp $testCp com.eventseat.server.WebDatabaseFixture 5001
```

Wait for `FIXTURE_READY:5001`. **Press Enter when finished**, rather than killing
the process, so it closes clients and prints `FIXTURE_SCHEMA_DROPPED`. An abrupt
process/OS termination may leave a schema named `eventseat_web_test_<random>`;
inspect it explicitly before manual cleanup.

Terminal B:

```powershell
Set-Location web
node tests/serve-fixture.mjs
```

Terminal C:

```powershell
Set-Location web
node tests/live-integration.mjs
```

The test refuses to mutate a server unless the expected isolated fixture event
is present. It books then cancels a test row, checks two clients, ownership,
lookup, leave/disconnect cleanup and waits through a real 60-second expiry.
Browser QA URL: `http://127.0.0.1:8081`. For a real JavaFX seat-screen peer:

```powershell
$testCp = (Get-Content .\target\web-java\classpath.txt -Raw).Trim()
& 'C:\Program Files\Java\jdk-27\bin\java.exe' --enable-native-access=ALL-UNNAMED -cp $testCp com.eventseat.client.WebJavaFxPeerCheck 5001
```

This opens EventSeatApp's real seat screen with its real ServerConnection on the
fixture port. After browser operations on A2, enter HELD, BOOKED or AVAILABLE to
assert the JavaFX button's state; HOLD fires its actual button. EXIT closes it.

For a physical demo, follow LAN_DEMO.md with one JavaFX client and two browser
clients. Verify hold/expiry, book, lookup, cancel, client counts and reconnect.
See WEB_VALIDATION.md for executed checks and remaining limits.
