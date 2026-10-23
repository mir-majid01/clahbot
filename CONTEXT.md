# AutoClash Project Context (V3)

## Overview
AutoClash (formerly ClahBOT; application id intentionally stays `com.clahbot`)
records taps, long presses and drags over the game with a floating controller and
replays them through an accessibility service. Educational use only.

## Architecture

```
com.clahbot/
├── ui/                 MainActivity (dashboard), SettingsActivity, theme/
├── service/            FloatingMenuService (controller + recorder + player),
│                       ClahAccessibilityService (gesture injection)
├── execution/          MacroPlayer, MacroTimeline (pure timing/geometry)
├── data/               BotState, MacroRepository, SettingsStore, models
└── util/               PermissionHelper
```

## State machine

`INACTIVE → READY → RECORDING ⇄ RECORDING_PAUSED → REVIEW → READY`
`READY → PLAYING ⇄ PLAYBACK_PAUSED → READY`
`ERROR` for missing accessibility or unplayable macros; `STOPPING` transiently
while playback is cancelled.

`BotState` is the only state source. The dashboard, controller and player read it.

## Data flow

1. Permissions: overlay + accessibility (+ notifications on Android 13).
2. **Start floating controller** starts `FloatingMenuService` as a foreground
   service and adds the controller window.
3. **Record** adds a full-screen capture surface and re-adds the controller on top.
4. Each touch is sampled into a `RecordedGesture` path and re-injected via
   `dispatchGesture`; the surface is `FLAG_NOT_TOUCHABLE` during the injection.
5. **Pause** makes the surface non-touchable and excludes paused time from gesture
   timestamps.
6. **Finish** removes the capture surface and moves to `REVIEW`.
7. **Save** writes through `MacroRepository`, which publishes the new list to every
   observer and selects the new macro.
8. **Play** starts `MacroPlayer` with the controller's speed and repeat mode.

## Persistence

`filesDir/macros.json` holds `{schemaVersion, macros[]}`. Writes are serialized by a
mutex, validated, and written to a temp file before replacing the original. Corrupt
files are quarantined. Schema 1 macros without a path are upgraded with a straight
line. `MacroRepository.macros` is a `StateFlow` and the single authoritative list;
the dashboard and controller never keep private copies.

Settings live in `SharedPreferences` behind `SettingsStore.settings: StateFlow`.

## Branding

AutoClash mark: hexagon + play triangle. Adaptive icon (`mipmap-anydpi-v26`),
monochrome themed icon, splash icon, notification icon and floating logo all derive
from it. Palette: navy `#0B1220`, electric blue `#3B82F6`, green `#22C55E`,
red `#EF4444`, amber `#F59E0B`.

## Build
GitHub Actions (`.github/workflows/android.yml`): unit tests, then `assembleDebug`,
then APK and test-report artifacts.
