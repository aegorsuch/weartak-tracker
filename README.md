# WearTAK-Tracker

Extremely lightweight Wear OS position tracker modeled on WearTAK-CIV.

## Features
- Reports position (PLI) to one or more TAK Servers (TLS, enrollment on 8446), Sit(x) TAK, and TAK SA UDP multicast.
- Constant or dynamic reporting (stationary / on foot / vehicle / while alerting intervals), same rules as CIV.
- CIV manual alerts (quick-text selection, cancel last alert) with store-and-forward: alerts are queued and sent in order when an endpoint reconnects.
- Settings: callsign, team, role, reporting strategy/intervals, TAK servers, multicast, Sit(x) (with Re-Auth and Remove).

## UI
Main screen mirrors CIV: alert button on top, callsign chip (opens settings), connection/location icons, time at the bottom.

## Build
```
.\gradlew.bat :app:assembleDebug
.\gradlew.bat :app:testDebugUnitTest
```
