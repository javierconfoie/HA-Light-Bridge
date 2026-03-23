# HA Light Bridge

Android app that bridges **Home Assistant** with **built-in RGB LED strips** on tablets that use serial-controlled LEDs. Originally designed for YS-branded Android tablets with embedded LED light strips.

## What it does

Turns your tablet's built-in LED strip into a fully controllable **Home Assistant light entity** via MQTT, with:

- **RGB color control** with gamma-corrected output (γ=2.2)
- **Brightness control** (0–255)
- **Configurable serial port** — select from available ports (`/dev/ttyS0`–`/dev/ttyS7`, `/dev/ttyUSB0`–`/dev/ttyUSB1`)
- **Effects**: Solid, Breathing, Flash, Crazy
- **Breathing speed** slider (exposed as a separate HA `number` entity, 1–10)
- **Screen brightness control** — adjusts tablet screen brightness via Android system API
- **Screensaver brightness** — separate brightness level when Fully Kiosk screensaver is active
- **Screensaver detection** — polls Fully Kiosk local API to detect screensaver state, exposed as `binary_sensor` in HA
- **Instant brightness switching** — anticipates screensaver exit by monitoring user interaction timestamps for near-zero-latency brightness transitions
- **State persistence** — remembers last color/brightness/effect across reboots
- **Auto-start on boot** — runs as a foreground service, no need to open the app after initial setup
- **Auto-reconnect** — recovers automatically if MQTT broker restarts
- **HA MQTT Discovery** — auto-registers as a light entity in Home Assistant

## Hardware

Designed for tablets with:
- **Serial LED controller** (default `/dev/ttyS3`, configurable) at **9600 baud**
- **3-channel analog RGB strip** (not individually addressable / not IC)
- Hardware color mapping: `LED.GREEN` → physical Red, `LED.BLUE` → physical Green, `LED.RED` → physical Blue

Tested on YS-branded Android tablets (Rockchip SoC, Android 14).

## Architecture

```
Home Assistant ←→ MQTT Broker ←→ [MqttLightService] ←→ Serial Port (configurable) ←→ LED Strip
                                         ↑
                                  Android Foreground Service
                                  (survives app close & reboot)
                                         ↓
                              Fully Kiosk REST API (localhost:2323)
                              → Screensaver state detection
                              → Android system brightness control
```

The app has two components:
1. **SettingsActivity** — Configure MQTT broker connection (host, port, user, password, device ID) and serial port. Only needed once.
2. **MqttLightService** — Foreground service that maintains the MQTT connection and controls LEDs via serial. Runs independently of any UI.

## MQTT Topics

| Topic | Direction | Description |
|-------|-----------|-------------|
| `{deviceId}/light/set` | HA → App | Light commands (JSON schema) |
| `{deviceId}/light/state` | App → HA | Current light state |
| `{deviceId}/light/availability` | App → HA | Online/offline status (LWT) |
| `{deviceId}/breathing_speed/set` | HA → App | Breathing speed (1–10) |
| `{deviceId}/breathing_speed/state` | App → HA | Current breathing speed |
| `homeassistant/light/{deviceId}/config` | App → HA | Auto-discovery config |
| `homeassistant/number/{deviceId}_breathing_speed/config` | App → HA | Speed slider discovery |
| `{deviceId}/screen_brightness/set` | HA → App | Screen brightness (1–255) |
| `{deviceId}/screen_brightness/state` | App → HA | Current screen brightness |
| `{deviceId}/screensaver_brightness/set` | HA → App | Screensaver brightness (1–255) |
| `{deviceId}/screensaver_brightness/state` | App → HA | Current screensaver brightness |
| `{deviceId}/screensaver/state` | App → HA | Screensaver active (ON/OFF) |
| `homeassistant/number/{deviceId}_screen_brightness/config` | App → HA | Screen brightness discovery |
| `homeassistant/number/{deviceId}_screensaver_brightness/config` | App → HA | Screensaver brightness discovery |
| `homeassistant/binary_sensor/{deviceId}_screensaver/config` | App → HA | Screensaver sensor discovery |

## Setup

1. Install the APK on the tablet (requires root or system-level serial port access)
2. Open **HA Light Bridge**
3. Enter your MQTT broker details (host, port, username, password)
4. Set a **Device ID** (e.g., `kitchen_tablet_led`)
5. Tap **Connect**
6. The light entity will appear automatically in Home Assistant

### Screen Brightness Control (optional)

Requires [Fully Kiosk Browser](https://www.fully-kiosk.com/) running on the same tablet with **Remote Administration** enabled.

1. In Fully Kiosk: **Settings → Remote Administration → Enable Remote Administration** and set a password
2. In HA Light Bridge: check **"Enable brightness control"** and enter the Fully Kiosk password
3. Tap **Save & Connect**
4. The first time, you'll be prompted to grant **"Modify System Settings"** permission — accept it
5. Three new entities will appear in HA:
   - `number.{deviceId}_screen_brightness` — brightness when screen is active (1–255)
   - `number.{deviceId}_screensaver_brightness` — brightness when screensaver is on (1–255)
   - `binary_sensor.{deviceId}_screensaver` — screensaver active state (ON/OFF)

The app polls Fully Kiosk's local REST API (`localhost:2323`) every 500ms to detect screensaver state changes, and applies the corresponding brightness via Android's system settings API. User interaction is anticipated via `lastUserInteractionTime` for near-instant brightness transitions when exiting the screensaver.

## Building

```bash
./gradlew assembleDebug
```

The APK will be at `app/build/outputs/apk/debug/app-debug.apk`.

### Requirements
- Android SDK (API 29+)
- Android Gradle Plugin 7.1.3
- JDK 11+

## Dependencies

- [Eclipse Paho MQTT Client 1.2.5](https://www.eclipse.org/paho/) — MQTT communication
- `serialport.jar` + native `.so` libraries (included in `app/libs/`) — JNI serial port access

## Project Structure

```
app/src/main/java/com/ys/serialportdemo/
├── MqttLightService.java   # Core MQTT↔LED bridge (foreground service)
├── SettingsActivity.java    # MQTT configuration UI
├── MainActivity.java        # Original LED test UI
└── PowerOnReceiver.java     # Boot receiver (auto-starts service)

app/libs/
├── arm64-v8a/libserial_port.so
├── armeabi-v7a/libserial_port.so
└── serialport.jar
```

## License

This project is provided as-is for educational and personal use.
