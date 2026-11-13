# AutoClash Project Context (V4)

## Overview
AutoClash (formerly ClahBOT; application id intentionally stays `com.clahbot`)
records touches over the game with a floating controller, replays them through an
accessibility service, reads loot and resource counters with OCR, and runs Auto
Walls 2.0 — a safety-gated wall-upgrade pipeline. Educational use only.

## Modules

```
:app        the product (service, dashboard, automation pipelines)
:ocr        Android library: text recognition (ML Kit + Tesseract4Android),
            digit parsing, icon colour sampling — no Android UI
:ocrlab     OCR lab + instrumented tests
:benchmark  macrobenchmark harness
```

## Architecture

```
com.clahbot/
├── ui/                 MainActivity (dashboard), design/ (Ds design tokens),
│                       dashboard cards, theme
├── service/            FloatingMenuService (controller pages, recorder, player
│                       host), ClahAccessibilityService (capture + gestures)
├── input/              Passive recording: getevent/Shizuku event sources,
│                       TouchModel/TouchTransform, PassiveRecorder
├── execution/          MacroPlayer (gate, stop policy), MacroTimeline
│                       (pure timing/geometry), PlaybackPlan
├── game/               GameScreenEngine (screen classification), GameState,
│                       GameInput (thread-safe suspend gestures)
├── ocr consumers:      results/ (loot capture: episodes, gates, screenshots),
│                       BitmapIconColorSampler bridges to :ocr
├── triggers/           Custom image triggers (template match → macro play)
├── recovery/           Smart-recovery watchdog, RecoveryRules, PlaybackGate
├── walls/              Auto Walls 2.0 (see below)
├── data/               BotState, MacroRepository, AttackRepository,
│                       SessionRepository, TriggerRepository, SettingsStore,
│                       AppEventLog, models
├── overlay/            Controller placement, dock, touch effects
├── update/             GitHubUpdater + UpdateNotifier (in-app updates)
├── session/            GameSessionTracker (attack session accounting)
└── util/               PermissionHelper, Haptics, Plural
```

## State machine

`INACTIVE → READY → RECORDING ⇄ RECORDING_PAUSED → REVIEW → READY`
`READY → PLAYING ⇄ PLAYBACK_PAUSED → READY`
`ERROR` for missing accessibility or unplayable macros; `STOPPING` transiently
while playback is cancelled.

`BotState` is the only state source. The dashboard, controller and player read it.

## Recording (passive)

Recording reads touch events instead of intercepting them: a getevent/Shizuku
`InputEventSource` runs while the user plays normally. `PassiveRecorder` parses
events through `TouchModel`/`TouchTransform`, filters touches on the controller's
own bounds, and builds `RecordedGesture`s with absolute timestamps (pause excluded).
The controller floats over the game; its window is masked from accessibility
captures so screen reads never see the bot's own UI.

## Playback

`MacroPlayer` replays gestures via `dispatchGesture` with per-macro speed,
repeat mode, repeat delay, start countdown and stop conditions (minutes/attacks/
background). The playback gate (`setGateHeld`) parks playback between actions —
smart recovery and Auto Walls use it to pause the attack loop, release it to
resume, or restart the macro when nothing was playing.

## Loot capture

A completed attack opens a capture session on the results screen: the OCR reader
runs through stability gates (`StableReadGate`, `RescanPolicy`, `ResultCaptureGate`)
and produces one `attacks.json` record per attack with nullable loot (null = not
observed). Screenshots for flagged episodes go to `ResultsScreenshotStore`.

## Auto Walls 2.0 (walls/)

The only wall system; the legacy wall-macro recorder/linking was removed. Dormant
`"wall"`-kind entries in macros.json stay filtered from every macro list, so
existing data is preserved but unused.

Pipeline, all unit-tested on the JVM through a `WallDevice` fake (observe / tap /
scroll / wait / now); the real adapter `AutoWallsDevice` is the only untested glue:

1. **Trigger** — `AutoWallsTrigger` (attacks between upgrades + gold/elixir
   thresholds and reserves, persisted counters). A controller "Run now" bypasses
   the counters with a synthetic passing evaluation; nothing else is bypassed.
2. **Scan** — `WallCounters` reads the live gold/elixir strip by icon side,
   `WallDiscovery` finds wall rows (level, count, price, currency) across the
   frame, `WallSelection` ranks and filters them (strategy, Pay-with, confidence).
3. **Select & tap** — the machine re-locates the chosen row on the current scan
   before every tap; stale coordinates are never replayed. Confirmation dialogs
   are re-validated (`UpgradeConfirmation`) and the reserve re-checked against
   the dialog's actual price (`AutoWallsReserve`) right before confirm.
4. **Verify** — an upgrade counts only when a post-upgrade re-scan proves the
   wall count dropped (`UpgradeVerification`); a successful tap alone proves
   nothing and feeds no counters.
5. **Repeat** — repeat upgrades require the switch on, a verified previous
   upgrade and a spend proof, capped by `maxRepeats`.

Safety gates, none bypassable by configuration: `allowTapping` defaults false
(machine stops at SAFETY_BLOCKED before any tap), only HOME may start a run,
popups/unknown screens/other apps stop it, and `never-press` boxes (gem buttons,
rings, captions) refuse any gesture overlapping them. The dashboard Auto Walls 2.0
card and the controller WALLS page are read-only scan surfaces; the controller's
Allow-tapping switch needs a two-step in-panel confirm. Scan reports persist the
balances plus affordable wall counts per currency (cheapest rows first).

## Persistence

All writes are mutex-serialized, validated, temp-file + rename; corrupt files are
quarantined, never trusted. Gson models are sanitized on load (stale keys ignored,
values coerced).

- `filesDir/macros.json` — attack macros (`MacroRepository`, StateFlow is the
  single authoritative list)
- `filesDir/attacks.json` — attack history with nullable loot
- `filesDir/auto_walls_{config,run,history,trigger,scan}.json` — Auto Walls 2.0
  state; a stored scan never restores `allowTapping=true`
- `filesDir/triggers.json`, `recovery.json`, sessions — triggers, recovery rules,
  game sessions
- Settings live in `SharedPreferences` behind `SettingsStore.settings: StateFlow`.

## Branding

AutoClash mark: hexagon + play triangle. Adaptive icon (`mipmap-anydpi-v26`),
monochrome themed icon, splash icon, notification icon and floating logo all derive
from it. Palette: navy `#0B1220`, electric blue `#3B82F6`, green `#22C55E`,
red `#EF4444`, amber `#F59E0B`.

## Build

- `.github/workflows/android.yml` (push main / PR / manual): unit tests, then
  debug APK + test-report artifacts for `:app` and `:ocrlab`.
- `.github/workflows/release.yml` (tag `v<versionCode>`): signed release APK
  published as a GitHub release (`AutoClash-<versionName>.apk`).

Release APKs are only ever built in CI. The JVM gate that must pass before any
ship: `./gradlew --no-daemon --stacktrace testDebugUnitTest` (all modules,
unfiltered).
