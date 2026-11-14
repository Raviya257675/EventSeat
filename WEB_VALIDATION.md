# Web integration validation — 2026-11-14

Branch: `feature/web-ui-integration`, created from the existing `feature/lan-demo`
working tree. No staging, commit, push, PR, merge, reset or clean was performed.
The supplied ZIP was inspected and extracted; its source, images, font dependencies
and styles were used. The supplied audit was treated as reference, not an instruction
to change backend semantics.

## Actual results

| Check | Result / scope |
| --- | --- |
| Dependency install | PASS: npm install with repository-local target/npm-cache; package-lock updated for ws |
| TypeScript | PASS: npm run typecheck; also executed by production build |
| React production build | PASS: npm run build; web/dist/index.html and bundled CSS/JS/fonts/images exist |
| Node tests | PASS: 5 tests covering protocol validation/parsing, HTTP isolation, session ownership, fragmented TCP replies, interleaved monitoring, FIFO, timeouts, reconnect and stale frontend callbacks |
| Fresh Java compilation | PASS: JDK 27 javac --release 25, all main/test sources, fresh output directory |
| NetworkReliabilityTestMain --embedded | PASS: 10/10 |
| SeatHoldTestMain --embedded | PASS, including real 60-second expiry |
| HeartbeatTestMain --embedded | PASS |
| UiRegressionCheck | PASS; classpath JavaFX harness emits its existing unnamed-module warning |
| Production data read | PASS: browser rendered Music Night 2026, Tech Conference 2026 and Cultural Festival 2026 from the already-running Java server; no production booking/cancellation was issued |
| HTTP/WebSocket | PASS: served production assets, per-browser sessions, live counts and measured gateway-to-Java RTT |
| Two WebSocket clients + real Java/MySQL | PASS: holds, ownership rejection, release, BOOKED/AVAILABLE broadcasts, numeric booking ID, cross-client lookup, wrong-name rejection, cancellation and CANCELLED lookup |
| Real expiry through gateway | PASS: held for 60 seconds; expiry broadcast observed; expired booking rejected; heartbeat remained healthy |
| Browser full booking flow | PASS: browser seat hold, invalid-name rejection, confirmed booking, lookup and cancellation against disposable MySQL schema; localStorage stayed empty |
| Two actual browser tabs | PASS: second tab saw HELD/disabled seat; refresh released original session; new session loaded current availability |
| Real JavaFX screen + browser | PASS: EventSeatApp seat button became gold/disabled on browser HOLD, red/disabled on BOOKED, green/enabled on cancellation; JavaFX button HOLD became HELD/disabled in browser |
| Disconnect/restart/reconnect | PASS: stopping fixture cleared online stats and displayed Reconnect; restarted fixture + Reconnect restored current seats |
| QR and printing | PASS: QR SVG exists; Print ticket invokes browser print; ticket visible under print CSS. Physical printer NOT TESTED |
| Responsive view | PASS: 390px-wide seat view had no page overflow; desktop discovery screenshot inspected |
| Secret/static boundary | PASS: db.properties, .env, source/config/traversal HTTP requests returned 404; only web/dist is served |
| Git ignore / preservation | PASS: node_modules, web/dist, target, .playwright-mcp, db.properties and portable binaries ignored; all pre-existing files except deliberately extended README/.gitignore retained identical SHA-256 hashes |
| Physical multi-laptop / hotspot demo | NOT TESTED |
| Production database writes | NOT RUN deliberately; real writes tested in disposable schemas instead |
| Maven clean/test | NOT RUN: Maven absent from PATH; fresh javac compilation and explicit standalone checks used as the equivalent, preserving existing outputs |
| Portable EXE rebuild | NOT RUN in this integration; prior tested image and packaging files preserved |

No unresolved failing implementation tests remain. Initial tool/setup failures
were corrected: npm's default cache was sandbox-restricted, so installation used
target/npm-cache; an intermediate frontend build ran before its source replacement
completed, then passed after completion; fixture port 5000 was occupied, so isolated
tests used 5001 without stopping the existing server. The first failed-bind fixture
dropped its schema. All subsequent fixture schemas were dropped normally.

## Commands actually executed

From repository root unless otherwise noted:

```powershell
git status --short
git switch -c feature/web-ui-integration
# In web/:
npm.cmd install --no-fund --no-audit --cache ..\target\npm-cache
npm.cmd run typecheck
npm.cmd test
npm.cmd run build
# From root:
powershell -NoProfile -ExecutionPolicy Bypass -File scripts/Build-Java.ps1
powershell -NoProfile -ExecutionPolicy Bypass -File scripts/Start-Web.ps1
```

The regression invocation used the freshly compiled `target/web-validation/classes`
plus the existing `target/runtime-classpath.txt` dependencies:

```powershell
$jdk = 'C:\Program Files\Java\jdk-27\bin'
$testCp = 'target/web-validation/classes;' + (Get-Content target/runtime-classpath.txt -Raw).Trim()
& "$jdk\java.exe" -cp $testCp com.eventseat.server.NetworkReliabilityTestMain --embedded
& "$jdk\java.exe" -cp $testCp com.eventseat.server.SeatHoldTestMain --embedded
& "$jdk\java.exe" -cp $testCp com.eventseat.server.HeartbeatTestMain --embedded
& "$jdk\java.exe" --enable-native-access=ALL-UNNAMED -cp $testCp com.eventseat.client.UiRegressionCheck
```

The new Build-Java.ps1 is the reproducible equivalent without relying on a previous
classpath file. It compiled every production/test source successfully. Integration:

```powershell
$testCp = (Get-Content target/web-java/classpath.txt -Raw).Trim()
& "$jdk\java.exe" -cp $testCp com.eventseat.server.WebDatabaseFixture 5001
# Separate terminal in web/:
node tests/serve-fixture.mjs
node tests/live-integration.mjs
# Separate terminal at root:
& "$jdk\java.exe" --enable-native-access=ALL-UNNAMED -cp $testCp com.eventseat.client.WebJavaFxPeerCheck 5001
```

Browser checks were executed with Playwright against actual local HTTP/WebSocket
servers, not mocked React data. The JavaFX check used the real EventSeatApp seat
screen and real ServerConnection on the fixture port. Three bookings were created
and cancelled in the first fixture schema, and one print-check booking in the
restart fixture. Fixture SQL counted cancelled rows before dropping the schemas.
The original configured database's rows were not changed.

Local evidence (ignored): `target/web-validation/*TestMain.log`,
`target/web-validation/UiRegressionCheck.log`, `target/web-validation/baseline.json`,
desktop screenshot and Playwright snapshots. Fixture and gateway test processes
are stopped after validation; the pre-existing Java server on 5000 is left alone.

## Files created or modified

Modified pre-existing files: `.gitignore`, `README.md`.

New documentation/scripts/tests:

- WEB_INTEGRATION.md, WEB_VALIDATION.md
- scripts/Build-Java.ps1, scripts/Start-JavaServer.ps1, scripts/Start-Web.ps1
- src/test/java/com/eventseat/server/WebDatabaseFixture.java
- src/test/java/com/eventseat/client/WebJavaFxPeerCheck.java

New `web/` source tree:

- .gitignore, index.html, package.json, package-lock.json, tsconfig.json, vite.config.ts, README.md
- src/App.tsx, src/BookingViews.tsx, src/connection.ts, src/hooks.ts, src/data.ts,
  src/storage.ts (type-only; no persistence), src/main.tsx, src/styles.css,
  src/integration.css, src/print.css, src/vite-env.d.ts
- shared/protocol.ts, gateway/server.mjs
- tests/helpers.mjs, tests/protocol.test.mjs, tests/gateway.test.mjs,
  tests/connection.test.mjs, tests/live-integration.mjs, tests/serve-fixture.mjs
- public/favicon.svg and public/images/{classical,electronic,jazz,midnight,sundown,theatre}.jpg

Existing uncommitted EventSeatApp/ClientConfiguration/ClientConfigurationCheck,
LAN_DEMO.md, PORTABLE_CLIENT.md, scripts/Package-Client.ps1 and Start-Client.bat
were preserved, not replaced by this work. Existing generated portable app remains
at `dist/EventSeat-Client/`. No production Java file changed in this integration.

## Known compatibility limits

Single-seat bookings, numeric IDs and ID+name lookup are intentional. Prices,
categories, full venue floor plans, event start times, payment and account login
are not in the Java protocol. Event serialization still uses its existing delimiters
(events use `|`/`;`, booking detail fields use `:`); such delimiters inside database
event names/venues are not newly escaped. Use protocol-compatible event metadata.
If a booking commits but its response is lost, the old protocol has no request ID
or name-only recovery query; the gateway reports an unknown outcome and never
retries automatically. A demonstrator may need to inspect the server database.
This is a trusted-LAN integration, not a public authenticated commerce service.
