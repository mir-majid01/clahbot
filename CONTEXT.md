# ClahBOT Project Context (V1 - Game Booster Mode)

## Overview
ClahBOT is an Android macro recorder for Clash of Clans (educational use only). V1 focuses on one job: **Record an attack → Save it → Play it back continuously**, like Infinix Game Booster.

## Architecture (V1)

```
com.clahbot/
├── ui/                 # MainActivity (macro list only), OnboardingActivity
├── service/            # FloatingMenuService, AutomationForegroundService, RecordingService, ClahAccessibilityService, MediaProjectionHolder, FrameCaptureHolder
├── execution/          # MacroPlayer (playback with coordinate scaling, speed, repeat)
├── overlay/            # FloatingDashboard (playback progress overlay)
├── data/               # ConfigRepository, SharedVisionBuffer, models (Macro, RecordedGesture)
├── monitor/            # Battery/thermal watchdog
└── vision/             # Deprecated - FrameProcessor (no longer used)
```

## Key Data Flow
1. User grants screen capture + accessibility + overlay permissions
2. User presses **Start Floating** → `FloatingMenuService` shows draggable floating menu
3. User presses **Enter Game** → opens Clash of Clans
4. User presses **Record** on floating menu → full-screen touch-capture overlay appears below floating menu
5. Overlay intercepts all touches, records them with timestamps, re-injects via AccessibilityService (so the game responds)
6. User presses **Stop** → recording ends, Save/Re-record buttons appear
7. User presses **Save** → dialog asks for name → macro saved as JSON in Room (`bot_settings` table)
8. To play: tap a macro in the list → press **Play Loop** → `AutomationForegroundService` starts with MacroPlayer
9. `MacroPlayer` replays gestures with coordinate scaling, speed multiplier, and repeat count

## Recording Format
- Coordinates are normalized (0.0-1.0) for resolution independence
- Timestamps are relative to recording start (milliseconds)
- Gesture types: `tap`, `swipe`, `long_press`
- Screen dimensions and orientation stored with each macro

## Floating Menu Features
- **Fully draggable** anywhere on screen by touch
- **Record** → starts touch capture overlay (intercepts and re-injects touches)
- **Stop** → stops recording, shows Save/Re-record
- **Save** → asks for name via dialog, saves to Room DB
- **Re-record** → discards and starts new recording
- **Play Loop** → plays selected macro in infinite loop
- **Delete** → deletes selected macro
- **Close** → removes floating menu and stops service
- **Macro List** → shows saved macros, tap to select

## Home Screen (MainActivity)
- Title: "ClahBOT"
- Top buttons: **Start Floating**, **Enter Game**
- "Recorded Attacks" list: name, action count, duration, date, device info
- Each macro has Play and Delete buttons

## Playback Settings (stored per macro)
- `repeatMode`: "once", number, or "infinite"
- `speedMultiplier`: Float (1.0 = normal)
- `randomOffset`: "off", "small" (4px), "medium" (12px)

## Building
```powershell
$env:JAVA_HOME = "C:\Program Files\Android\Android Studio\jbr"
$env:ANDROID_HOME = "$env:LOCALAPPDATA\Android\Sdk"
.\gradlew.bat assembleDebug
```

**APK:** `app/build/outputs/apk/debug/app-debug.apk` (~58 MB)
