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
| `ResultsCaptureCoordinator` | Watches continuously from playback start (or a manual watch) and records every new result screen; a manual watch can capture an already-open screen if it was not recorded before. |
| `ResultCaptureGate` | Holds a late macro tap (up to 10 s) while a detected result screen is captured and read, so Return Home cannot dismiss it first. |
| `AppEventLog` | Rolling private diagnostic log (256 KB + one rotation) of playback, polls, reads and records; no screenshots or loot values. |

## Attack history

A detected result screen creates one confirmed attack record with start/end
time, macro snapshot, speed, repeat and the read loot. Playback completion
alone is never recorded. Loot (gold, elixir, dark elixir and bonuses) is read
on-device; a field that was not read stays `loot not captured`, never zero.
Deleting a macro never deletes its past attack records.

While the floating controller is running, an **Auto watch** session starts by
itself; no press is needed. It keeps watching for result screens and records
each one once. The Stats **Watch results** button remains available for an
explicit hand-played watch ("Manual watch"), and macro playback uses the macro
name; these labels now show the real source. A watch stays armed for up to 60
minutes and keeps watching after each capture, so every new result in that
session can become one record. If the screen is already open when a watch
starts, it is read and recorded only when no recent record has the same screen
hash or loot. Every screen is recorded at most once, even when the next poll
sees a different OCR frame of the same screen.

Loot values are accepted only after two consecutive complete reads agree; a
mid-animation frame is never saved. Later reads of the same open screen may
only fill missing fields of that record. Implausible values and balances that
contradict the capture are marked **Needs review** instead of being treated as
normal captures. The Edit attack loot dialog prefills all six fields (including
the bonus) and saving it keeps the bonus unless you clear it. A bonus panel
that is present but unreadable is shown differently from a screen with no
bonus panel.

Each record shows how it was produced: **auto-read**, **re-scanned**, or
**manually corrected**. If both the clean and detection frames are unreadable,
the screen still creates exactly one confirmed record with `loot not captured`
and the screenshot is kept. The dashboard has **Re-scan unreadable** to re-read
saved screenshots with the current reader; a record is updated only when all
three main loot values are produced, and the original screenshot is kept.

Saved capture triples (PNG, JSON, TXT) are capped at 200 captures or 800 MB.
When over a limit, the oldest triples are deleted first; the capture just
written is always kept. Records are not deleted, so an old record may point to
a screenshot that has been aged out.

The rolling diagnostic log is `files/autoclash_events.log`; pull it
with `adb exec-out run-as com.clahbot cat files/autoclash_events.log`.

Each macro also stores a **wait between repeats** (0–600 seconds, default
0.8 seconds for existing macros; 0 means no extra wait). Edit it with the
pencil on the macro card in the dashboard or the floating controller. The wait
applies to Loop and every repeat count. During the wait the controller status
and the collapsed bubble show a countdown; **Stop** cancels it immediately,
and if Clash leaves the foreground the wait pauses and the notification reports
the pause. Delays below one second still apply but do not show a countdown.

## Known limitations

- Passive recording requires a working Shizuku service or root. Without either,
  recording is unavailable rather than falling back to a touch-blocking overlay.
- Playback requires the AutoClash accessibility service.
- Rotating the screen stops a recording.
- Coordinates are normalized to the recording display; a very different aspect
  ratio can shift touch positions.
- Speed changes apply at the next playback run.
- The v2.4.8 build is **arm64-only** because the bundled OCR native libraries
  made the universal APK about 151 MB. The lab and app are verified on the
  arm64 test phone; other ABIs are not packaged in this build.
- Result-screen OCR needs the accessibility screenshot permission (Android 11+)
  and the game to be in the foreground while the result screen is open. A macro
  playback that starts while a result screen is already open still ignores that
  screen as stale; a manual watch may capture it when it has not been recorded
  recently. A missing or partial read stays `loot not captured`.
- A manual watch runs for up to 60 minutes per arm; press **Watch results**
  again after that or after pressing **Stop**. It does not start automatically
  when you begin a hand attack.
- The screen-hash duplicate guard is deliberately time-limited to 10 minutes.
  If an identical screen is still open after that window and you re-arm the
  watch, a duplicate record is possible; this is unlikely in normal play.
- Playback pauses when Clash of Clans leaves the foreground and the session is
  marked interrupted; resuming is manual. This includes the wait between
  repeats.
- The repeat-delay countdown is shown for waits of one second or more; a
  sub-second wait still applies. The wait is per macro and is not scaled by the
  playback speed.
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
git tag v12
git push origin v12
```

Version numbers live in `app/build.gradle.kts`.

## Credits

- Developer: **Mir Majid**
- Clash of Clans profile: **M-A-J-I-D**
- GitHub: [mir-majid01](https://github.com/mir-majid01)
- Source: [mir-majid01/clahbot](https://github.com/mir-majid01/clahbot)
