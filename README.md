# WearTAK-Tracker

Extremely lightweight Wear OS position tracker modeled on WearTAK-CIV.

## Features
- Reports position (PLI) to one or more TAK Servers (TLS, enrollment on 8446), Sit(x) TAK, and TAK SA UDP multicast.
- Constant or dynamic reporting (stationary / on foot / vehicle / while alerting intervals). Dynamic reporting classifies movement from GPS speed (like ATAK) and uses the significant-motion sensor to leave the stationary interval as soon as the wearer moves.
- CIV manual alerts (quick-text selection, cancel last alert) with store-and-forward: alerts are queued in memory and sent in order when an endpoint reconnects. The queue does not survive the app process being killed.
- Reporting starts only when the app is opened; nothing starts automatically at boot.
- Optional physiological monitoring (off by default): heart rate and, on Samsung Galaxy watches, skin temperature are added to each PLI like CIV.
- Settings: callsign, team, role (MIL/LEO), reporting strategy/intervals, TAK servers, multicast (address, output protocol, port), Sit(x) (with Re-Auth and Remove).
  Menus follow CIV's hierarchy: WearTAK Preferences > Callsign and Device Preferences / Network Preferences.

## UI
Main screen is kept minimal: alert button, callsign chip (opens settings), status icons in the corners, time at the bottom.
- Top left: TAK/Sit(x)/multicast connection (tap for Network Preferences). Top right: location (tap for Reporting Strategy).
- Bottom left: watch network (Wi-Fi, cellular, Bluetooth via phone, airplane mode, or none). Bottom right: battery (red at 20% or below).
The location icon is white while reporting with precise location, yellow with approximate location, and grey when stopped.

## Setup
1. Install the APK on the watch (`adb install WearTAK-Tracker-<version>-debug.apk`).
2. Open the app and grant **location** (precise or approximate) and, on Wear OS 4+, **notifications**.
3. Tap the callsign chip > **Callsign and Device Preferences** to set callsign, team and role.
4. Under **Network Preferences**, add a TAK Server, enable TAK SA Multicast, and/or configure Sit(x).

### Permissions
| Permission | Why |
|---|---|
| Location (precise or approximate) | Required. Without either, the tracker does not start. |
| Body sensors | Only when Physiological Monitoring is turned on. Needed to read heart rate (and skin temperature) from the foreground service. |
| Notifications | Shows the ongoing "Reporting every Ns" notification required for a location foreground service. |

**Precise vs approximate:** both work. With approximate location the system coarsens every fix (TAK clients will see a large CE), the notification says "approximate location", and dynamic reporting cannot classify speed, so it stays on the "moving/unknown" interval (the larger of on-foot and vehicle). Grant precise location in system settings and reopen the app to switch; no restart is needed.

### Physiological monitoring
Off by default (CIV defaults to on); enable it under **Callsign and Device Preferences** (the toggle shows the latest HR and skin temperature). When on, each PLI carries CIV's `<remarks>Exert:N/A%;HR:<bpm>;SkinTemp:<°F></remarks>` and `<biometrics>` block.
- **Heart rate** comes from the standard heart-rate sensor, using only high-accuracy samples, and is sent as N/A when the watch is off-wrist or no reading is newer than 5 minutes.
- **Skin temperature** uses the Samsung Health Sensor SDK (same AAR as CIV), one reading per minute while worn, in °F. Other watches and the emulator report N/A. The SDK is proprietary and not committed: copy `samsung-health-sensor-api-1.4.1.aar` into `app/libs/` before building. Without it the build still succeeds and skin temperature is always N/A. Samsung partner approval is tied to the package name/signing key, so `com.tak.weartak_tracker` must be registered (or Health Platform developer mode enabled) for readings.
- **Exertion** needs age and resting heart rate, which the tracker doesn't collect, so it is always N/A.

On the emulator, set a heart rate with `adb emu sensor set heart-rate 72`.

### Dynamic reporting
Each precise fix with a reliable GPS speed is classified as stationary (< 0.5 m/s), on foot (≤ 4.5 m/s) or vehicle. Switching state needs 2 consecutive fixes (3 to become stationary) so GPS noise or a stop at a light does not change the interval. While stationary, the significant-motion sensor is armed; When it fires, the tracker takes a fresh fix immediately. Watches without that sensor only leave stationary at the next stationary-interval fix.

### TAK Server certificates
The tracker connects with a client certificate obtained in one of three ways, in order:
1. **Sideloaded P12** for the server's address (see below). Username/password are then optional.
2. **Cached enrollment** from a previous connection (renewed automatically 3 days before expiry).
3. **Enrollment** with username/password against `https://<address>:8446`.

#### Sideloading a certificate
Copy files named after the exact address entered in the server form into the app's external files directory:
```
adb push tak.example.com.p12 /sdcard/Android/data/com.tak.weartak_tracker/files/certs/tak.example.com.p12
adb push tak.example.com.pwd /sdcard/Android/data/com.tak.weartak_tracker/files/certs/tak.example.com.pwd
```
- `<address>.p12` or `<address>.p12.b64` (base64 text) – client key, certificate and the server's CA chain.
- `<address>.pwd` – optional P12 password (default `atakatak`).

#### Server identity checks
The server certificate must chain to a CA in the client P12/enrollment response **and** be issued for the configured address:
- Hostnames must match a DNS subjectAltName (a `*.` wildcard covers one label).
- IP addresses must match an IP subjectAltName.
- Certificates without any subjectAltName are matched against their CN (common for TAK Server certs made with `makeCert.sh`).

**Automatic TLS name discovery** (same rules as WearTAK-iOS/CIV): when the server is reached by an IP or another name that the certificate doesn't list, the tracker accepts the certificate's name only if the chain comes from the server's private CA (not a public CA) **and** the certificate has exactly one exact DNS subjectAltName. That name is saved as the server's TLS name (shown in Edit Server) and used for SNI and validation from then on. The CoT stream and the 8443 API save their names separately. A saved name is never replaced automatically. It is cleared when the address or port is edited. Certificates with several names, wildcards only, or no subjectAltName are rejected.

If the server form shows `TLS: Certificate is for [...], not <address>`, enter the address that appears in the brackets (add a DNS entry if needed) or reissue the server certificate with the address as a subjectAltName.

### Channels
**Network Preferences > Channels** lists each TAK Server. Open one to see its channels (TAK Server groups) and turn them on or off, as in WearTAK-CIV. The list loads after each connect, when the page is opened or **Refresh** is tapped, and when the server announces a group change. A toggle shows "Updating..." until the server confirms, and it reverts if the request fails. Channels use the client certificate on `https://<address>:8443/Marti/api/groups/...`, which requires TAK Server's group cache (otherwise "Channels unsupported").

## Troubleshooting
| Symptom | Check |
|---|---|
| Location icon grey, no notification | Location permission denied. Grant it in system settings and reopen the app. |
| `No certificate: add username/password or sideload ...` | Neither credentials nor a sideloaded P12 for that exact address. File names are case-sensitive. |
| `Enrollment rejected: bad username/password` | Credentials are wrong or the user lacks enrollment rights on the TAK Server. |
| `Enrollment failed: ...` on 8446 | Port 8446 unreachable or the enrollment certificate isn't trusted by the watch; sideload a P12 instead. |
| `TLS: Certificate is for [...]` | Address doesn't match the server certificate and discovery wasn't possible (see above). |
| Channels: "Not connected" / error | The TAK Server must be connected, and port 8443 reachable with the same client certificate. |
| `TLS: handshake failed` repeatedly | After 3 failures the cached enrollment is discarded and re-enrolled. Check the server's CA is in the P12. |
| Multicast never shows connected | Multicast needs Wi-Fi (Bluetooth/LTE do not carry it). Check the address is 224.0.0.0–239.255.255.255. |
| Reporting interval never drops to stationary | Approximate location, or GPS speed accuracy too poor indoors. |
| Sit(x) stuck on "Authorize" | Open the verification URL on a phone and enter the code shown under Sit(x) State. |

Logs: `adb logcat -s TrackerService TakServerManager TakCertificates TakChannelManager TakChannelApi SitxClient MulticastPublisher`

## Project Information

**Rights:** Unlimited rights granted to TAK Product Center.

**Point of contact:** Alex Gorsuch on chat.tak.gov or Signal.

**Repositories:** The [TAK Forge repository](https://git.tak.gov/core/weartak-core/weartak-tracker) is canonical. [GitHub](https://github.com/aegorsuch/weartak-tracker) is a secondary repository.

## Build
```
.\gradlew.bat :app:assembleDebug
.\gradlew.bat :app:testDebugUnitTest
```
CI (`.github/workflows/android.yml`) runs the unit tests and builds the debug APK on every push and pull request.

### Release signing
Release builds are signed only when a `signing.properties` file exists in the project root (it is git-ignored, as are `*.jks`/`*.keystore`):
```
storeFile=release.jks
storePassword=...
keyAlias=weartak-tracker
keyPassword=...
```
Create a key once with `keytool -genkeypair -v -keystore release.jks -alias weartak-tracker -keyalg RSA -keysize 4096 -validity 10000`, keep it backed up (updates must be signed with the same key), then run `.\gradlew.bat :app:assembleRelease`. Without the file, `assembleRelease` produces an unsigned APK.
