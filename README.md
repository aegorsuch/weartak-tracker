# WearTAK-Tracker

Extremely lightweight Wear OS position tracker modeled on WearTAK-WearOS

## Downloads
Download installable APKs from [GitHub Releases](https://github.com/aegorsuch/weartak-tracker/releases), or go directly to the [latest release](https://github.com/aegorsuch/weartak-tracker/releases/latest). Expand **Assets** and select `WearTAK-Tracker-<version>-release.apk`; the source-code archives are not watch installers.

Version **5.8.0.3 / 583** is distributed as an optimized, non-debuggable APK signed with the permanent Tracker release key. Updates from the release-signed 5.8.0.3 build can use `adb install -r` while retaining settings. Moving from an earlier debug-signed build requires uninstalling first (`adb uninstall com.tak.weartak_tracker`), which removes app data and settings, then installing the release APK.

**Updated 5.8.0.3 build:** The manual Alert picker adds 911 Alert, Ring The Bell, Geofence Breached, and In Contact, alphabetizes all presets, and sends immediately when a preset is tapped. Custom text and alert cancellation retain their confirmation buttons.

**Windows sideloading:** [Wear OS Windows Bridge](https://github.com/aegorsuch/wearos-windows-bridge) is a useful companion tool for pairing/connecting to your watch, installing downloaded APKs, capturing logs, and mirroring the screen. Enable Wireless Debugging on the watch and follow the bridge's setup instructions; the PC and watch need to be on the same Wi-Fi for these operations. Normal Tracker operation can use LTE afterward.

## Design intent
WearTAK-Tracker is intended primarily for standalone **LTE-connected watches**, with long-duration battery life as a core design goal. TAK Server and Sit(x) reporting over LTE are the primary use cases; Bluetooth/phone-relay and other connection-specific features are not a development priority. Existing network status indicators remain informational.

For an LTE deployment, disable **TAK SA Multicast** under Network Preferences: it is a Wi-Fi-only option, not an LTE delivery route. Tune reporting intervals for the required position freshness and leave physiological monitoring off unless needed. Actual battery life depends on coverage, GPS use, reporting intervals, and enabled sensors.

## Features
- Reports position (PLI) to one or more TAK Servers (TLS, enrollment on 8446), Sit(x) TAK, and TAK SA UDP multicast.
- Constant or dynamic reporting (stationary / on foot / vehicle / while alerting intervals). Dynamic reporting classifies movement from GPS speed (like ATAK) and uses the significant-motion sensor to leave the stationary interval as soon as the wearer moves.
- PLI stale time is twice the active reporting interval plus 15 seconds (60-second reporting expires after 135 seconds). When reporting stops, the last transmitted PLI expires naturally; no immediate clear message is sent. Manual alerts retain their 15-minute stale time.
- Manual alerts with an alphabetized, tap-to-send picker: 911 Alert, Gate Runner, Geofence Breached, Gunshot, Gunshot Injury, In Contact, Injury, Ring The Bell, UAS, and Vehicle. Selecting a preset sends it immediately without an extra confirmation; custom text still uses the confirm button. Cancel the last alert with confirmation. Store-and-forward queues alerts in memory and sends them in order when an endpoint reconnects; the queue does not survive the app process being killed. Incoming alerts are not displayed on the watch.
- Connected alerts and cancellations request a fresh GPS fix and send immediately without waiting for it. Only a valid position less than 3 seconds old is attached; missing, stale, future-dated, or invalid coordinates use unknown-position values. A new fix triggers an updated PLI; the original alert is not resent. Failed sends remain queued in order, and a usable TAK Server, Sit(x), or multicast route drains that queue before rebroadcasting PLI. Cancellation selection and queue changes are serialized together.
- Reporting starts only when the app is opened; nothing starts automatically at boot.
- Tracker has its own Recent Apps task. Swiping it away stops reporting, sensors, connections, and scheduled location alarms. Simply returning to the watch face leaves tracking running; reopen the app after closing its task to restart.
- PLI is resent immediately using the latest known fix when a network changes, an endpoint reconnects, or the callsign/settings button is tapped. If no fix is available yet, a single fix is requested instead.
- Saving a changed callsign, team, or role requests a fresh location and immediately resends PLI with the new identity and latest known fix, without waiting for GPS. The fresh fix sends an updated PLI when it arrives. Unrelated settings changes do not trigger this extra request.
- Optional physiological monitoring (off by default): heart rate and, on Samsung Galaxy watches, skin temperature are added to each PLI like CIV.
- Settings: callsign, team, role (MIL/LEO), BATDOK/medical profile, reporting strategy/intervals, TAK servers, multicast (address, output protocol, port), Sit(x) (with Re-Auth and Remove).
  TAK Server Connections and the Channels server picker display servers alphabetically by name, ignoring capitalization; saved order and connection behavior are unchanged.
  Menus follow CIV's hierarchy: WearTAK-Tracker Preferences > Callsign and Device Preferences / Network Preferences.

## UI
Main screen is kept minimal: alert button, callsign chip (opens settings), status icons in the corners, time at the bottom.
Long callsigns are truncated with an ellipsis on the main screen; the saved and transmitted callsign remains unchanged.
Settings refresh immediately after saving, including medical profile values and the physiological monitoring switch. TAK Server passwords are visible while editing, matching regular WearTAK. Long-press an editable text field to select text and open watch-sized Copy, Paste, Cut, and Select all actions when available; copied passwords enter the Android clipboard, so use care when screen sharing.
- Top left: TAK/Sit(x)/multicast connection (tap for Network Preferences). Top right: location (tap for Reporting Strategy).
The TAK icon is green when all enabled, distinct TAK Servers are connected and split red/green when only some are connected. Disabled and duplicate server entries do not count against it; servers still connecting or without runtime status count as not connected.
- Bottom left: watch network (Wi-Fi, cellular, Bluetooth via phone, airplane mode, or none). Bottom right: battery (red at 20% or below).
The location icon is white while reporting with precise location, yellow with approximate location, and grey when stopped.

## Setup
1. Download the APK from [GitHub Releases](https://github.com/aegorsuch/weartak-tracker/releases) and install it on the watch (`adb install -r WearTAK-Tracker-<version>-release.apk`). Uninstall the old debug-signed app first if migrating to the release key; this clears settings.
2. Open the app and grant **location** (precise or approximate) and, on Wear OS 4+, **notifications**.
3. Tap the callsign chip > **Callsign and Device Preferences** to set callsign, team and role.
4. Under **Network Preferences**, add a TAK Server, enable TAK SA Multicast, and/or configure Sit(x).

### Sit(x) setup
Enter your organization/address under **Network Preferences > Sit(x) TAK**, enable the service, and authorize the watch using the code/URL in **Sit(x) State**, then select a group. Tracker supplies the same default public OAuth client ID as WearTAK-CIV; there is no Client ID entry in the submenu. Previously saved custom IDs are preserved, and existing blank values use the default automatically.

The Network Preferences entry shows **Enabled** or **Disabled**, matching WearTAK-CIV. This describes the service setting, not live connectivity; check **Sit(x) State** for authorization, connection, and errors. The submenu contains the **Sit(x) TAK** toggle, **Address**, **Group**, **Sit(x) State**, **Re-Auth**, and **Remove**.

### Permissions
| Permission | Why |
|---|---|
| Location (precise or approximate) | Required. Without either, the tracker does not start. |
| Body sensors | Only when Physiological Monitoring is turned on. Needed to read heart rate (and skin temperature) from the foreground service. |
| Notifications | Shows the ongoing "Reporting every Ns" notification required for a location foreground service. |

**Precise vs approximate:** both work. With approximate location the system coarsens every fix (TAK clients will see a large CE), the notification says "approximate location", and dynamic reporting cannot classify speed, so it stays on the "moving/unknown" interval (the larger of on-foot and vehicle). Grant precise location in system settings and reopen the app to switch; no restart is needed.

### Physiological monitoring
Off by default (CIV defaults to on); enable it under **Callsign and Device Preferences** (the toggle shows the latest HR, skin temperature, and exertion). When on, each PLI carries physiological `<remarks>` and `<biometrics>`, regardless of the BATDOK setting. The separate **BATDOK** toggle controls only the `<_atmist_>` block.
- **Heart rate** comes from the standard heart-rate sensor, using only high-accuracy samples, and is sent as N/A when the watch is off-wrist or no reading is newer than 5 minutes.
- **Skin temperature** uses the Samsung Health Sensor SDK (same AAR as CIV), one reading per minute while worn, in °F. Other watches and the emulator report N/A. The SDK is proprietary and not committed: copy `samsung-health-sensor-api-1.4.1.aar` into `app/libs/` before building. Without it the build still succeeds and skin temperature is always N/A. Samsung partner approval is tied to the package name/signing key, so `com.tak.weartak_tracker` must be registered (or Health Platform developer mode enabled) for readings.
- **Exertion** uses the same Karvonen heart-rate-reserve calculation as regular WearTAK: `max(0, HR - resting HR) / (208 - 0.7 × age - resting HR)`. Age comes from birth year; resting HR defaults to 60 bpm and is editable in Medical Profile. This is an estimate, not a measured resting baseline or medical assessment. Missing age/HR or an invalid reserve produces N/A. The settings toggle displays a percentage; CoT retains CIV's fraction format (`0.50` means 50%, including its legacy `Exert:0.50%` remarks). Values above the estimated maximum can exceed 100%, as in CIV.

### BATDOK and medical profile
**Callsign and Device Preferences > BATDOK** is on by default, matching WearTAK. It adds BATDOK-compatible `<_atmist_>` vital signs (heart rate and skin temperature in Fahrenheit/Celsius) to PLI while Physiological Monitoring is active. No vital sign is fabricated when a sensor reading is unavailable. Turning BATDOK off leaves the physio remarks, biometrics, and sensor collection on; turning Physiological Monitoring off omits all physiological PLI data. Manual-alert CoT is unchanged.

Under **My User Metrics > Medical Profile (BATDOK)**, set birth year, height (feet/inches), weight (lbs), sex, blood type, allergies (multiple selections), user type, and resting heart rate. The profile persists across app restarts and is encrypted at rest. As in WearTAK's current CoT builder, only age (calculated from birth year) is included in the BATDOK block; the other profile fields are stored locally, not transmitted. Birth year and resting HR also drive the exertion value in remarks and biometrics. Unset birth year omits age rather than inventing one.

On the emulator, set a heart rate with `adb emu sensor set heart-rate 72`.

### Dynamic reporting
Each precise fix with a reliable GPS speed is classified as stationary (< 0.5 m/s), on foot (≤ 4.5 m/s) or vehicle. Switching state needs 2 consecutive fixes (3 to become stationary) so GPS noise or a stop at a light does not change the interval. While stationary, the significant-motion sensor is armed; When it fires, the tracker takes a fresh fix immediately. Watches without that sensor only leave stationary at the next stationary-interval fix.

### Developer mode and network admin lock
In **WearTAK-Tracker Preferences**, tap the version label eight times, with no more than 1.5 seconds between taps, to toggle developer mode (matching CIV). The version turns red and shows the build code. **Dev Debug Tools** displays read-only build, device, network, endpoint, and reporting information. Developer mode resets when the app process starts again.

With developer mode enabled, open **Dev Debug Tools > Stay awake** to keep the screen, CPU, and Wi-Fi awake for development and demos. In addition to the Wi-Fi lock, it actively requests a Wi-Fi network without requiring internet access, keeping demand for Wear OS Wi-Fi while enabled without binding Tracker's default/LTE traffic to it. Wireless debugging is not required, and disabling it or dismissing the app's recent task does not stop Stay awake. The adjacent **Resume after update** checkbox opts in to restoring an explicitly enabled session after an in-place app update. Turning off Stay awake or using the notification's **Stop** action clears that session; no automatic resume happens after an ordinary process restart or watch reboot. Locks and the Wi-Fi request are released when the service is destroyed and reacquired only for an eligible update restore. There is no timed expiry. This uses extra battery and may heat the watch; it does not override manually disabled Wi-Fi, prevent an explicit screen-off action, or guarantee ADB delivery.

Open **Beta Features > Network Settings Lock** to block Network Preferences, its subpages, and the main-screen network shortcut without stopping configured connections or reporting. The lock persists across app restarts. To unlock, enable developer mode again and turn the same switch off. This is CIV's UI administration lock, not PIN authentication or an Android device-management policy.

### TAK Server certificates
The tracker connects with a client certificate obtained in one of three ways, in order:
1. **Sideloaded P12** for the server's address (see below). Username/password are then optional.
2. **Cached enrollment** from a previous connection (renewed automatically 3 days before expiry).
3. **Enrollment** with username/password against `https://<address>:8446`.

Enrollment retains the watch's normal HTTPS trust and hostname validation. Certificate configuration must parse successfully before generating a CSR or requesting a signature. Signing responses accept JSON or namespace-aware XML, including PEM certificates, with a required `signedCert` and optional numbered `caN` certificates in numeric order. Errors identify the failed stage (configuration, request, signing, certificate, or connection) and distinguish HTTP 401, other HTTP failures, TLS, connectivity, and invalid certificate data. Saving waits for settings to persist; **Retry** starts a fresh attempt for that server even when settings have not changed. Rejected credentials still require correction; retry does not bypass authentication.

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
| `Client certificate: no client certificate...` | Neither credentials nor a sideloaded P12 for that exact address. File names are case-sensitive. |
| `Certificate configuration/signing: authentication rejected (HTTP 401)...` | Credentials are wrong or the user lacks enrollment rights on the TAK Server. |
| `Certificate signing: server rejected request (HTTP 403).` | The server denied enrollment; check server-side authorization. |
| `Certificate configuration/signing: secure connection failed...` / `server unreachable...` | Check port 8446, network access, and the enrollment certificate's trust and hostname; sideload a P12 if needed. |
| `Certificate configuration` / `Client certificate: invalid or missing certificate data.` | Server response is malformed or required certificate data is missing; check enrollment configuration. |
| `TLS: Certificate is for [...]` | Address doesn't match the server certificate and discovery wasn't possible (see above). |
| Channels: "Not connected" / error | The TAK Server must be connected, and port 8443 reachable with the same client certificate. |
| `TAK connection: secure connection failed...` repeatedly | After 3 TLS failures the cached enrollment is discarded and re-enrolled. Check the server's CA is in the P12. |
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

The published release key is configured locally in `release.jks` and `signing.properties`; neither file is committed. Back up both securely together. Losing the key prevents in-place updates to installed release builds. Debug builds and CI APK artifacts remain development-only and use a different signing key.
