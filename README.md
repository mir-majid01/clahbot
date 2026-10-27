# AutoClash

Android macro recorder for Clash of Clans. Record real taps, long presses and
multi-finger drags with a floating controller, then replay them with adjustable
speed and repeat.

**Educational use only.** Automation violates Clash of Clans' Terms of Service
and may get your account banned.

## Install

1. Download `AutoClash-<version>.apk` from
   [Releases](https://github.com/mir-majid01/clahbot/releases/latest).
2. Allow your browser or file manager to install unknown apps.
3. Open AutoClash and grant **Accessibility**, **Display over other apps** and
   notifications.
4. Passive recording needs **Shizuku** running (recommended, no root) or root.
   Shizuku permission is granted once; updates from inside the app keep it.

Every release is signed with the same committed key, so installing a newer
version over an older one keeps macros, attacks, settings and permissions.
Uninstalling the app deletes its local data.

## Workflow

1. Start the floating controller and open the game.
2. Tap **Record**. In passive mode your touches go straight to the game while
   AutoClash reads the raw touchscreen through Shizuku or root.
3. Play the attack, then tap **Finish**.
4. **Review** lets you save, discard, or record again.
5. Select a saved macro, set speed and repeat, then tap **Play**.
6. During playback the panel collapses to a small draggable logo placed in the
   top corner that the macro touches least.
7. After playback, AutoClash watches for the result screen and reads the loot
   on-device through the multi-reader OCR core. A detected result screen creates
   one confirmed attack record; a failed read stays `loot not captured` and can
   be corrected from the history. A playback ending alone never creates a record.

## Recording modes

| Mode | What happens |
|---|---|
| **Passive** (default) | No AutoClash window is in the touch path. Raw `getevent` data is read as the shell user through Shizuku or root. The game receives every touch normally, with real timestamps and multi-touch. |
| **Blocks game input** (capture-only) | A full-screen transparent overlay captures touches and the game receives none. Kept only for deliberate offline recording; the controller labels it clearly. |

Passive recording stops automatically if the screen rotates or the capture
source fails. The indicator shows `STARTING`, `REC`, or `PAUSED`.

## Settings

- **Recording access** — live Shizuku/root status and one explicit grant button.
- **Recording & playback** — mode, default speed, default repeat, haptics,
  keep screen awake.
- **Floating controller** — start/open, start minimized, opacity, controller
  size, floating icon size, reset position. Size changes apply when the
  controller is reopened.
- **Permissions & diagnostics** — accessibility, overlay and notification state.
- **Results capture (debug)** — optionally save each detected result screen as
  a PNG plus a JSON/TXT sidecar containing the app's own read. Files stay in
  the app's external `results_captures` folder and are never uploaded.
- **Macro storage / Attack history** — counts, disk usage and deletion tools.
- **Appearance** — dark, light or system theme.
- **Updates** — installed version, check GitHub, download and install.
- **Credits** — developer and profile links.
- **About** — privacy, licences and version.

## Updates

The app checks the public GitHub Releases API on request. A release must be
tagged `v<versionCode>` (for example `v5`) and contain an `.apk` asset plus an
`.apk.sha256` checksum. The updater downloads to the app cache, verifies the
SHA-256 and exact size, then opens Android's package installer through a
`FileProvider`. No macro or gameplay data is uploaded.

## Release signing

`keystore/autoclash-release.jks` is committed on purpose so every APK has the
same signature. A stable signature is required for in-place updates and for
keeping Shizuku permission. The file and password are public; only install
updates from this repository's Releases. See
[keystore/README.md](keystore/README.md).

## Architecture

| Component | Responsibility |
|---|---|
| `FloatingMenuService` | Foreground service that owns the controller window, passive recorder and player. |
| `InputEventSource` | Runs `/system/bin/getevent` as shell through Shizuku, or through `su` on rooted phones. |
| `PassiveRecorder` | Splits raw `getevent` batches, parses Linux multi-touch protocol B and emits completed gestures with kernel timestamps. |
| `PassiveGestureBuilder` | Tracks slots, builds independent paths for simultaneous fingers and ignores touches inside the controller bounds. |
| `MacroPlayer` | Replays macros with `AccessibilityService.dispatchGesture`, including simultaneous paths. |
| `MacroRepository` | Single authoritative macro list exposed as `StateFlow`, persisted as versioned JSON. |
| `MacroTimeline` | Pure timing and geometry: speed scaling, classification, decimation, repeat modes. |
| `BotState` | One state machine shared by the dashboard and controller. |
| `SettingsStore` | Shared preferences as a `StateFlow`. |
| `GitHubUpdater` | Release check, checksum-verified download and installer launch. |
| `:ocr` | Shared on-device result-screen reader: ML Kit full/crop, Tesseract, RapidOCR PP-OCRv4 and digit templates with strict-majority consensus. |
| `ResultsCaptureCoordinator` | Watches for the result screen from playback start (or a manual watch) through four minutes after playback ends, reads it, and creates one confirmed attack record only when the results anchors are actually detected. |
| `ResultCaptureGate` | Holds a late macro tap (up to 10 s) while a detected result screen is captured and read, so Return Home cannot dismiss it first. |
| `AppEventLog` | Rolling private diagnostic log (256 KB + one rotation) of playback, polls, reads and records; no screenshots or loot values. |

## Attack history

A detected result screen creates one confirmed attack record with start/end
time, macro snapshot, speed, repeat and the read loot. Playback completion
alone is never recorded. Loot (gold, elixir, dark elixir and bonuses) is read
on-device; a field that was not read stays `loot not captured`, never zero.
Deleting a macro never deletes its past attack records.

The controller's Stats tab has **Watch results** for a hand-played attack. It
runs the same full-resolution detector without a macro and records only when a
result screen is detected. During a macro, a late tap (the Return Home tap) is
held until a detected result screen has been captured and read, for at most
10 seconds. The rolling diagnostic log is `files/autoclash_events.log`; pull it
with `adb exec-out run-as com.clahbot cat files/autoclash_events.log`.

## Known limitations

- Passive recording requires a working Shizuku service or root. Without either,
  recording is unavailable rather than falling back to a touch-blocking overlay.
- Playback requires the AutoClash accessibility service.
- Rotating the screen stops a recording.
- Coordinates are normalized to the recording display; a very different aspect
  ratio can shift touch positions.
- Speed changes apply at the next playback run.
- The v2.4.2 build is **arm64-only** because the bundled OCR native libraries
  made the universal APK about 151 MB. The lab and app are verified on the
  arm64 test phone; other ABIs are not packaged in this build.
- Result-screen OCR needs the accessibility screenshot permission (Android 11+)
  and the game to be in the foreground while the result screen is open. The
  result screen is watched for from playback start, because a recorded macro
  normally taps Return Home while it is open. A result screen that was already
  open before playback is ignored as stale; a missing or partial read stays
  `loot not captured`.
- Playback pauses when Clash of Clans leaves the foreground and the session is
  marked interrupted; resuming is manual.
- The results race gate applies to taps in the last 15 seconds of a macro and
  may add up to 2 seconds before such a tap. It can hold the tap for up to
  8 seconds (about 10 seconds including the fresh-sample wait) while the
  capture completes. A hand tap in manual-watch mode is not held, so leave the
  result screen open until the app reports it.
- The diagnostic log is capped at 256 KB with one rotated copy and contains no
  screenshots, loot values or macro names.

## Build and release

- `main` and pull requests: `.github/workflows/android.yml` runs unit tests and
  assembles a debug APK signed with the stable key.
- Release: push a tag `v<versionCode>` (or run the release workflow manually).
  `.github/workflows/release.yml` runs tests, assembles the signed release APK,
  writes its SHA-256 and publishes both as GitHub release assets.

```bash
git tag v11
git push origin v11
```

Version numbers live in `app/build.gradle.kts`.

## Credits

- Developer: **Mir Majid**
- Clash of Clans profile: **M-A-J-I-D**
- GitHub: [mir-majid01](https://github.com/mir-majid01)
- Source: [mir-majid01/clahbot](https://github.com/mir-majid01/clahbot)
