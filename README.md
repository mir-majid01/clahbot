# ClahBOT

Android macro recorder for Clash of Clans: record taps, long presses and drags
with the floating controller, then replay them with adjustable speed and looping.

**Educational use only.** Automation violates Clash of Clans' Terms of Service and
may get your account banned.

## How it works

1. Open ClahBOT and grant **Display over other apps** and **Accessibility**.
2. Tap **Start Floating**, then **Enter Game**.
3. In the controller, tap **Record**, play the attack normally, then **Stop Rec**.
4. Name and save the macro, select it in the list, set speed and loop, then **Play**.
5. **—** collapses the controller to a draggable logo; tap the logo to reopen it.

## Architecture

| Component | Responsibility |
|---|---|
| `FloatingMenuService` | Foreground service. Owns the controller window, the capture surface and the player. |
| `MacroPlayer` | Replays a macro through `AccessibilityService.dispatchGesture`. Only one player is active at a time. |
| `MacroRepository` | Versioned JSON file, validated and written atomically behind a mutex. |
| `MacroTimeline` | Pure timing/geometry rules: speed scaling, gesture classification, path thinning. |
| `BotState` | Single state machine (`IDLE`/`RECORDING`/`READY`/`PLAYING`/`PAUSED`/`STOPPING`/`ERROR`). |

### Window layering

Two overlay windows exist while recording:

1. A full-screen transparent **capture surface**.
2. The **controller**, added *after* the capture surface so it is always on top.

Touches on the controller never reach the capture surface, so pressing a button
cannot be recorded as a gameplay gesture. Touches elsewhere are recorded, then
re-injected into the game through the accessibility service.

Injected gestures would otherwise land on our own capture surface, so the surface
is given `FLAG_NOT_TOUCHABLE` for the duration of each dispatch and restored when
the gesture completes.

## Known limitations

Android does not expose a raw touch stream from another app to a normal
application; accessibility only reports semantic events, not `MotionEvent`s.
Recording therefore **intercepts and re-injects** touches rather than observing
them. Consequences, in order of importance:

- While recording, the game receives the *injected* gesture. Expect one frame of
  latency, and expect a brief window around each injection during which a new
  touch is not captured. This is inherent to the approach, not a bug.
- Drag paths are sampled (~6 px) and thinned to at most 240 points. Playback
  rebuilds the path geometry and the total duration, but per-point timestamps are
  not reproduced exactly — one `StrokeDescription` interpolates along the path.
- A tap is a gesture; a touch that moves less than 24 px is recorded as a tap, and
  a stationary touch longer than 500 ms as a long press.
- Coordinates are normalized to the recording display. Playing a portrait macro
  in landscape (or on a very different aspect ratio) will land in the wrong place.
- Rotating the screen stops recording; the controller recomputes display bounds.
- Changing speed while a macro is playing applies to the next run.
- Playback requires the ClahBOT accessibility service to be enabled; recording
  additionally requires the overlay and (Android 13+) notification permissions.

## Build

CI builds and tests on every push:

```bash
git push origin main          # GitHub Actions: unit tests + assembleDebug
```

Locally, if Maven Central is reachable:

```bash
JAVA_HOME="/c/Program Files/Android/Android Studio/jbr" ./gradlew assembleDebug
```

The APK is uploaded as the `clahbot-debug` workflow artifact, and also lands at
`app/build/outputs/apk/debug/app-debug.apk`.

## Layout

```
app/src/main/java/com/clahbot/
├── data/          BotState, MacroRepository, models
├── execution/     MacroPlayer, MacroTimeline
├── service/       FloatingMenuService, ClahAccessibilityService
├── ui/            MainActivity, OnboardingActivity, SplashActivity
└── util/          PermissionHelper
```
