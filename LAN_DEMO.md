# Three-laptop Windows LAN demo

Use the same `feature/lan-demo` source on all three laptops, with Java and Maven
available in PowerShell (`java -version`, `mvn -version`). Use a JDK compatible
with the JavaFX version in `pom.xml` (this build was checked with JDK 27).
Run the commands from each laptop's project directory.

Only laptop 1 runs MySQL and ServerMain. Keep its existing local database
configuration and populated database. Do not copy `db.properties`, credentials,
or server-built `target` output to the client laptops. Clients need neither a
MySQL installation nor database configuration: they communicate through TCP.

## Laptop 1: server

Connect all laptops to the same trusted LAN/Wi-Fi. Find laptop 1's Wi-Fi or
Ethernet IPv4 address (for example `192.168.1.10`):

```powershell
ipconfig
```

On a trusted Windows Private network, allow inbound TCP 5000. Run this once in
an elevated PowerShell on laptop 1 (or use an equivalent existing firewall rule):

```powershell
New-NetFirewallRule -DisplayName 'EventSeat LAN Demo TCP 5000' -Direction Inbound -Action Allow -Protocol TCP -LocalPort 5000 -Profile Private -RemoteAddress LocalSubnet
```

Start MySQL using your existing setup, then run in ordinary PowerShell:

```powershell
mvn compile dependency:build-classpath '-Dmdep.outputFile=target/runtime-classpath.txt'
$runtimeCp = 'target/classes;' + (Get-Content target/runtime-classpath.txt -Raw).Trim()
java -cp $runtimeCp com.eventseat.server.ServerMain
```

Leave this terminal running. Expect `Listening on port 5000`. ServerMain already
uses `new ServerSocket(port)`, which binds to the wildcard address and accepts
LAN connections; no bind-address change is needed. Do not open MySQL's port or
configure router port forwarding for this demo.

## Laptops 2 and 3: one JavaFX client each

On **each** client laptop, replace the example address with laptop 1's actual
LAN IPv4 address, then run all commands in the same PowerShell window:

```powershell
$env:EVENTSEAT_SERVER_HOST = '192.168.1.10'
Test-NetConnection -ComputerName $env:EVENTSEAT_SERVER_HOST -Port 5000
mvn compile javafx:run
```

Expect `TcpTestSucceeded : True`. The variable takes a host/IP only, without
`http://` or `:5000`. Port remains 5000. Initial connection and the Reconnect
button both read this variable. Environment changes apply to newly launched
processes; restart the client to change hosts. Missing, empty, or whitespace-only
values default to `localhost`; surrounding whitespace is trimmed.

For a local client on laptop 1, remove the override before launching:

```powershell
Remove-Item Env:EVENTSEAT_SERVER_HOST -ErrorAction SilentlyContinue
mvn compile javafx:run
```

If connection fails, check the server terminal, IPv4 address, Private firewall
profile, and whether Wi-Fi guest/client isolation blocks laptop-to-laptop traffic.

## Demonstration checklist

1. Open both remote clients; verify online count reaches 2 and heartbeat latency
   appears. A temporary `Test-NetConnection` probe may briefly affect the count.
2. Open the same event on both. Select a seat on one client and confirm the other
   sees its hold. Book it and confirm both show the booked status.
3. Find that booking using its ID/customer name and cancel it; confirm both
   clients see the seat become available.
4. Open different events and verify updates stay scoped to the relevant event.
5. Close one client and check the online count. Restart it, then stop ServerMain
   with Ctrl+C, restart ServerMain, and click Reconnect on both clients. Verify
   seats refresh, heartbeats resume, and the count returns to 2.

Use demo records: bookings and cancellations persist in the server database.

## Regression checks (no database needed)

These are executable checks, not JUnit tests; run them explicitly:

```powershell
mvn compile test-compile dependency:build-classpath '-Dmdep.outputFile=target/runtime-classpath.txt'
$testCp = 'target/test-classes;target/classes;' + (Get-Content target/runtime-classpath.txt -Raw).Trim()
Remove-Item Env:EVENTSEAT_SERVER_HOST -ErrorAction SilentlyContinue
java -cp $testCp com.eventseat.client.ClientConfigurationCheck localhost
$env:EVENTSEAT_SERVER_HOST = '192.168.1.10'
java -cp $testCp com.eventseat.client.ClientConfigurationCheck 192.168.1.10
java -cp $testCp com.eventseat.server.NetworkReliabilityTestMain --embedded
java -cp $testCp com.eventseat.server.SeatHoldTestMain --embedded
java -cp $testCp com.eventseat.server.HeartbeatTestMain
java --enable-native-access=ALL-UNNAMED -cp $testCp com.eventseat.client.UiRegressionCheck
```

After the demo, optionally remove the demo firewall rule in elevated PowerShell:

```powershell
Remove-NetFirewallRule -DisplayName 'EventSeat LAN Demo TCP 5000'
```
