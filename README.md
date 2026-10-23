# AutoClash

Android macro recorder for Clash of Clans: record taps, long presses and drags with
a floating controller, then replay them with adjustable speed and repeat.

**Educational use only.** Automation violates Clash of Clans' Terms of Service and
may get your account banned.

## Workflow

1. Open AutoClash and grant **Accessibility** and **Display over other apps**
   (notifications are recommended on Android 13+).
2. Tap **Start floating controller**, then **Launch** to open the game.
3. On the controller: **Record**, play the attack, then **Finish**.
4. The controller switches to **Review**: **Save** (name it), **Discard**, or **Again**.
5. Select the macro, set speed and repeat, then **Play**.
6. **—** collapses the controller to a draggable logo; tap it to reopen without
   losing an active recording or playback.

## Branding

- Name/label: **AutoClash** (application id stays `com.clahbot`, so updates keep
  working).
- Adaptive launcher icon, monochrome themed icon, splash icon and the floating
  logo all use the same hexagon-and-play mark.
- Palette: navy surfaces, electric-blue primary, green = ready, red = recording
  and destructive, amber = paused/warning.

## Architecture

| Component | Responsibility |
|---|---|
| `FloatingMenuService` | Foreground service. Owns the controller window, capture surface, recorder and player. |
| `MacroPlayer` | Replays a macro through `AccessibilityService.dispatchGesture`. One active player. |
| `MacroRepository` | Single authoritative macro list, exposed as `StateFlow`, persisted as versioned JSON. |
| `MacroTimeline` | Pure timing/geometry: speed scaling, classification, path thinning, repeat modes. |
| `BotState` | One state machine: `INACTIVE`, `READY`, `RECORDING`, `RECORDING_PAUSED`, `REVIEW`, `PLAYING`, `PLAYBACK_PAUSED`, `STOPPING`, `ERROR`. |
| `SettingsStore` | Shared preferences as `StateFlow`: default speed/repeat, haptics, keep-awake, opacity, start-minimized, theme. |

### Controller states

- **Ready** — Record, macro list, Play, speed, repeat.
- **Recording** — the panel collapses to a compact red widget with a timer,
  Pause/Resume and Finish. Pausing makes the capture layer non-touchable, so the
  game receives touches normally and nothing is recorded.
- **Review** — Again / Discard / Save only.
- **Playing** — macro name, state, Pause/Resume, Stop, speed and repeat.
- **Minimized** — logo only; recording and playback keep running.

### Deletion synchronisation

The dashboard and the controller both read `MacroRepository.macros`. Every save,
rename, delete and clear-all publishes a new list; the service reconciles its
selection, stops playback if the macro being played disappears, and re-renders.
There is no second copy of the library.

### Window layering and touch capture

While recording two overlay windows exist: a full-screen transparent **capture
surface**, then the **controller** re-added on top. Touches on controller controls
never reach the capture surface, so they are never recorded. Touches elsewhere are
sampled, then re-injected through the accessibility service; the surface is made
`FLAG_NOT_TOUCHABLE` during each injection so the injected gesture reaches the game
instead of our own overlay.

## Settings

- Recording & playback: default speed, default repeat (Once / Loop / 3×), haptics,
  keep screen awake.
- Floating controller: start/stop, start minimized, opacity, reset position.
- Permissions & diagnostics: live status and a plain explanation of the touch-capture
  limitation.
- Macro storage: count, file size, delete all.
- Appearance: dark / light / system.
- About: version, GitHub link, Telegram (not configured), privacy, licences.

## Known limitations

Android does not expose another app's raw touch stream to a normal app, and the
accessibility service reports semantic events rather than `MotionEvent`s. Recording
therefore **intercepts and re-injects**:

- The game receives the injected gesture, not the original; expect about a frame of
  latency and a brief uncaptured window around each injection.
- Drag paths are sampled (~6 px) and thinned to at most 240 points. Playback rebuilds
  the path geometry and total duration; per-point timestamps are interpolated.
- Multi-touch is not supported; a stationary touch over 500 ms is a long press.
- Coordinates are normalized to the recording display; a very different aspect ratio
  will land in the wrong place. Rotating stops the recording.
- Speed changes apply to the next playback run.

## Build

CI builds and tests on every push (`.github/workflows/android.yml`):

```bash
git push origin main
```

Artifacts: `clahbot-debug` (APK) and `unit-test-report`. Local builds work when
Maven Central is reachable:

```bash
JAVA_HOME="/c/Program Files/Android/Android Studio/jbr" ./gradlew assembleDebug
```

## Layout

```
app/src/main/java/com/clahbot/
├── data/          BotState, MacroRepository, SettingsStore, models
├── execution/     MacroPlayer, MacroTimeline
├── service/       FloatingMenuService, ClahAccessibilityService
├── ui/            MainActivity (dashboard), SettingsActivity, theme/
└── util/          PermissionHelper
```
