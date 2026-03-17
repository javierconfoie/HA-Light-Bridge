# HA Light Bridge

Android app that bridges **Home Assistant** with **built-in RGB LED strips** on tablets that use serial-controlled LEDs (via `/dev/ttyS3`). Originally designed for YS-branded Android tablets with embedded LED light strips.

## What it does

Turns your tablet's built-in LED strip into a fully controllable **Home Assistant light entity** via MQTT, with:

- **RGB color control** with gamma-corrected output (γ=2.2)
- **Brightness control** (0–255)
- **Effects**: Solid, Breathing, Flash, Crazy
- **Breathing speed** slider (exposed as a separate HA `number` entity, 1–10)
- **State persistence** — remembers last color/brightness/effect across reboots
- **Auto-start on boot** — runs as a foreground service, no need to open the app after initial setup
- **Auto-reconnect** — recovers automatically if MQTT broker restarts
- **HA MQTT Discovery** — auto-registers as a light entity in Home Assistant

## Hardware

Designed for tablets with:
- **Serial LED controller** on `/dev/ttyS3` at **9600 baud**
- **3-channel analog RGB strip** (not individually addressable / not IC)
- Hardware color mapping: `LED.GREEN` → physical Red, `LED.BLUE` → physical Green, `LED.RED` → physical Blue

Tested on YS-branded Android tablets (Rockchip SoC, Android 10/11).

## Architecture

```
Home Assistant ←→ MQTT Broker ←→ [MqttLightService] ←→ Serial Port (/dev/ttyS3) ←→ LED Strip
                                         ↑
                                  Android Foreground Service
                                  (survives app close & reboot)
```

The app has two components:
1. **SettingsActivity** — Configure MQTT broker connection (host, port, user, password, device ID). Only needed once.
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

## Setup

1. Install the APK on the tablet (requires root or system-level serial port access)
2. Open **HA Light Bridge**
3. Enter your MQTT broker details (host, port, username, password)
4. Set a **Device ID** (e.g., `kitchen_tablet_led`)
5. Tap **Connect**
6. The light entity will appear automatically in Home Assistant

After initial setup, the app runs as a background service. You can close/kill the app — the service persists. It also auto-starts on boot.

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
