# ClahBOT Project Context (V2)

## Overview
ClahBOT records taps, long presses and drags over the game with a floating
controller, then replays them through an accessibility service. Educational use
only.

## Architecture

```
com.clahbot/
├── ui/                 MainActivity (dashboard), OnboardingActivity, SplashActivity
├── service/            FloatingMenuService (controller + recorder + player),
│                       ClahAccessibilityService (gesture injection)
├── execution/          MacroPlayer (playback), MacroTimeline (pure timing/geometry)
├── data/               BotState (state machine), MacroRepository (versioned JSON)
└── util/               PermissionHelper
```

## Key data flow

1. User grants overlay + accessibility (+ notifications on Android 13).
2. **Start Floating** starts `FloatingMenuService` as a foreground service and
   adds the controller window.
3. **Record** adds a full-screen capture surface and re-adds the controller on
   top so controller buttons stay above the recorder.
4. Each touch on the capture surface is sampled into a `RecordedGesture` path and
   re-injected into the game via `dispatchGesture`. The capture surface is
   `FLAG_NOT_TOUCHABLE` while the injection is in flight so the injected gesture
   reaches the game instead of our own overlay.
5. **Stop Rec** removes the capture surface. With gestures captured the controller
   moves to `READY` and offers **Save** or **Discard**.
6. Saving writes a `Macro` through `MacroRepository` (versioned JSON, atomic write).
7. Selecting a macro loads its speed and loop settings; **Play** starts
   `MacroPlayer` with the controller's current speed and loop mode.
8. **—** collapses the panel to a draggable logo inside the *same* window, so
   minimizing never loses state or creates a second window.

## Recording format

- `Macro`: stable id, name, schema version (2), gesture list, repeat mode, speed.
- `RecordedGesture`: type (`tap` / `long_press` / `swipe`), relative timestamp,
  duration, start/end, and the full `points` path.
- `GesturePoint`: normalized x/y (0..1) plus milliseconds since gesture start.

Geometry is normalized to the recording display and scaled to the current display
at playback. Paths are thinned to at most 240 points before saving.

## Playback

- Speed 0.25x–4.0x scales both inter-gesture delays and stroke duration.
- Loop is a player feature (`repeatMode = "infinite"`), not a UI-only flag.
- Stop cancels the coroutine job and any pending dispatch wait; a stale callback
  cannot restart a stopped macro.
- Pause stops scheduling new gestures; the gesture already dispatched completes
  because Android cannot cancel a gesture mid-flight.

## Persistence

`filesDir/macros.json` holds `{schemaVersion, macros[]}`. All writes go through a
`Mutex`, are validated (non-empty gestures, clamped speed, monotonic timestamps),
and are written to a temp file before replacing the original. A corrupt file is
renamed to `macros.json.corrupt-<ts>` and the app starts with an empty list.
Schema 1 macros without a path are upgraded by rebuilding a straight line from the
stored start/end points.

## Building
Build is done in GitHub Actions (`.github/workflows/android.yml`): unit tests,
then `assembleDebug`, then the APK is uploaded as an artifact.
