# ClahBOT

Native Android automation engine for Clash of Clans, built per the PRD v1.1 specification.

## Architecture

Three decoupled async threads:

- **Vision Parsing** — MediaProjection screen capture, template matching, ML Kit OCR, red-line detection
- **Execution Orchestration** — Behavioral state machine with Bézier-curve gestures via AccessibilityService
- **System Monitor** — Battery, thermal, and Doze watchdog with auto-pause

## Features

- Search Only, Collect Only, Full Farm, and Builder Base modes
- Floating overlay dashboard with live status
- Onboarding wizard for all required permissions
- Human-like input: Gaussian delays, tap jitter, Bézier swipes
- Room database for configs, templates, and session logs
- Foreground service with notification controls

## Requirements

- Android 8.0+ (API 26)
- Accessibility Service permission
- Screen capture (MediaProjection) permission
- Overlay permission
- Battery optimization exemption

## Build

Open the project in Android Studio and sync Gradle, or:

```bash
./gradlew assembleDebug
```

Install the APK:

```bash
adb install app/build/outputs/apk/debug/app-debug.apk
```

## Usage

1. Complete the onboarding wizard (permissions)
2. Open Clash of Clans
3. Select automation mode (start with **Search Only** for safe testing)
4. Press **Start** and grant screen capture
5. Use the floating overlay to monitor status or stop

## Disclaimer

**Educational use only.** Automation violates Clash of Clans Terms of Service and may result in account bans. Use at your own risk.

## Project Structure

```
app/src/main/java/com/clahbot/
├── service/       Foreground service, accessibility, screen capture
├── vision/        Frame processing, OCR, template matching
├── execution/     State machine, gesture executor
├── monitor/       Watchdog (battery, thermal, doze)
├── overlay/       Floating dashboard
├── data/          Shared buffer, Room DB, config
└── ui/            Main screen, onboarding wizard
```
