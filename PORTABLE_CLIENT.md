# Portable EventSeat Windows client

## Build on Windows x64

From the project folder, run:

```powershell
powershell -NoProfile -ExecutionPolicy Bypass -File .\scripts\Package-Client.ps1
```

Requires the Windows x64 JDK 27 at `C:\Program Files\Java\jdk-27` (override
with `-JdkHome`). No Maven or WiX is needed. JavaFX's version comes from
`pom.xml`; Windows base, graphics and controls modular JARs are copied from
`$HOME\.m2\repository` or downloaded from Maven Central if absent. Override
the cache location with `-MavenRepository`. Internet is needed only for missing JARs.

The script compiles an explicit allowlist of GUI client sources, builds a fresh
client-only JAR, and runs JDK 27 `jpackage --type app-image`. JavaFX modules,
including Windows native libraries, are linked into the bundled runtime.
The entry point remains `com.eventseat.client.EventSeatApp`.
No project resources, database properties, MySQL connector, server classes,
or repository classes are copied. No TCP or booking logic is changed.

Output: `dist\EventSeat-Client\`. Intermediate files are under
`target\portable-client\`. Both directories are ignored by Git. Existing output
is never deleted: move a previous `dist\EventSeat-Client` elsewhere before rebuilding.

## Copy and run on each client laptop

Copy the **entire** `dist\EventSeat-Client` folder to each Windows x64 laptop,
using USB or a ZIP. Extract the ZIP first. Keep `app` and `runtime` beside the EXE.
Do not copy the source repository or the project's other `target` files.
No Java, Maven, VS Code, Docker, MySQL, PATH changes, or installation is required.

Double-click `Start-Client.bat`, type the server laptop's LAN IPv4 address
(for example `192.168.1.10`), and press Enter. Or from Command Prompt:

```bat
Start-Client.bat 192.168.1.10
```

An empty prompt keeps an existing `EVENTSEAT_SERVER_HOST`, otherwise localhost.
The launcher sets the variable only for this launch. The EXE can also be run
directly: it inherits `EVENTSEAT_SERVER_HOST`, defaulting to localhost when
missing or blank. Use a host/IP only, without `http://` or a port. Restart the
client when changing hosts. Initial connection and Reconnect use the same host.

Only the server laptop runs ServerMain and MySQL. TCP port 5000 must be reachable
over the LAN; allow it through the server's firewall on the trusted network.
See the repository's `LAN_DEMO.md` for server setup and the two-client checklist.
If connection fails, check the IP, server process, firewall and Wi-Fi isolation.
Never distribute `db.properties` or database credentials to the clients.

## Validation and limitations

The packaging script fails on tool errors, audits the client JAR allowlist,
checks the EXE and bundled JVM/module files, and checks Windows JavaFX natives.
It does not run the server or change database state. Test both remote clients
against the demo server for heartbeat, seat holds, booking and cancellation.
This is a Windows x64 app image, not an installer or a signed executable.
Windows may show an unsigned-app warning depending on local policy.

Validated locally on 2026-11-14 with JDK 27 and JavaFX 27.0.1:

- Fresh compilation and app-image packaging succeeded; EXE, JVM and runtime
  modules exist, including JavaFX base/graphics/controls and Windows DLLs.
- ClientConfigurationCheck passed with localhost fallback and a LAN IP override.
- NetworkReliabilityTestMain (10/10), SeatHoldTestMain, HeartbeatTestMain and
  UiRegressionCheck passed. Server checks used embedded fixtures, without MySQL.
- The packaged EXE opened a responsive `EventSeat` window with `JAVA_HOME`
  cleared and PATH restricted to Windows directories; startup stderr was empty.
- The UI regression harness emitted its existing classpath/unnamed-module
  JavaFX warning; the packaged client uses named JavaFX modules.

Physical two-laptop LAN operation and database-backed booking/cancellation were
not exercised by these local checks. Run the LAN demo checklist before presenting.
