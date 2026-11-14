# EventSeat Web

Integrated React client for the existing Java TCP/MySQL server. See
[WEB_INTEGRATION.md](../WEB_INTEGRATION.md) for Windows commands, architecture,
security scope and testing; see [WEB_VALIDATION.md](../WEB_VALIDATION.md) for results.

```powershell
npm.cmd ci
npm.cmd run build
npm.cmd start
```

Start Java ServerMain separately on TCP 5000. Open http://localhost:8080.
`npm test` runs protocol, gateway and frontend transport checks. The supplied
visual assets are retained; demo inventory and localStorage bookings are replaced
with real Java server operations. Build output is `dist/`, excluded from Git.
