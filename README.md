# WearTAK-Tracker

Extremely lightweight Wear OS position tracker modeled on WearTAK-CIV.

## Features
- Reports position (PLI) to one or more TAK Servers (TLS, enrollment on 8446), Sit(x) TAK, and TAK SA UDP multicast.
- Constant or dynamic reporting (stationary / on foot / vehicle / while alerting intervals), same rules as CIV.
- CIV manual alerts (quick-text selection, cancel last alert) with store-and-forward: alerts are queued in memory and sent in order when an endpoint reconnects.
- Reporting starts only when the app is opened; nothing starts automatically at boot.
- Settings: callsign, team, role, reporting strategy/intervals, TAK servers, multicast (address, output protocol, port), Sit(x) (with Re-Auth and Remove).
  Menus follow CIV's hierarchy: WearTAK Preferences > Callsign and Device Preferences / Network Preferences.

## UI
Main screen mirrors CIV: alert button on top, callsign chip (opens settings), connection/location icons, time at the bottom.

## Project Information

**Rights:** Unlimited rights granted to TAK Product Center.

**Point of contact:** Alex Gorsuch on chat.tak.gov or Signal.

**Repositories:** The [TAK Forge repository](https://git.tak.gov/core/weartak-core/weartak-tracker) is canonical. [GitHub](https://github.com/aegorsuch/weartak-tracker) is a secondary repository.

## Build
```
.\gradlew.bat :app:assembleDebug
.\gradlew.bat :app:testDebugUnitTest
```
