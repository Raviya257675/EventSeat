@echo off
setlocal DisableDelayedExpansion
if not exist "%~dp0EventSeat-Client.exe" (
    echo EventSeat-Client.exe is missing. Extract and copy the entire application folder.
    pause
    exit /b 1
)
if not "%~1"=="" (
    set "EVENTSEAT_SERVER_HOST=%~1"
) else (
    if not defined EVENTSEAT_SERVER_HOST set "EVENTSEAT_SERVER_HOST=localhost"
    echo Enter the server LAN IP or hostname. Press Enter to keep the current environment host or localhost.
    set /p "EVENTSEAT_SERVER_HOST=Server host: "
)
start "" "%~dp0EventSeat-Client.exe"
endlocal
