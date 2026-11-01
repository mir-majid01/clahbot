# AutoClash 2.8.1 — polish round report

Date: 2026-10-31  
Version: **2.8.1 (versionCode 24)**  
Installed APK: `app/build/outputs/apk/debug/app-debug.apk`  
SHA-256: `c97756607fa31564bd228124498f0c91343ac3bb96ac0fb7258f5203b7cc6b16`
R8 release APK: `app/build/outputs/apk/release/app-release.apk`
Release SHA-256: `b56b0f651385c0bf16ec8bc08dd69378cf867c97168773561bad2faad632250b`

The APK was installed in place over the previous 2.8.0 build. App data was preserved.
Automated checks: **208 app unit tests + 50 OCR-lab tests, 0 failures** (2 skipped),
**lint 0 errors / 137 warnings**, debug APK built, R8 release build succeeded.

The manual device acceptance is intentionally left to the user.

---

## A. Loot capture

### A1. Diagnosis of the last 12 attacks (evidence: live `attacks.json` + event logs)

| Category | Count | Evidence |
|---|---:|---|
| Attacks in the last 12 | 12 | `attacks.json` |
| Incomplete (`complete=false`) | 7 | records `1793385897025`, `1793386280446`, `1793386643448`, `1793386713418`, `1793387716878`, `1793387927062`, `1793357744781` |
| Complete but marked `review` | 1 | record `1793388007023` |
| Read saved as "loot not captured" | 7 | every one ends with `loot did not stabilize; saving without values` |
| First (“clean”) frame missed the anchors | 4 | `no 'You got' anchor` / `no damage anchor`; the frame was taken during the screen transition after the 140 ms overlay-hide delay |
| First read succeeded but the second stable frame never arrived | 7 | the screen closed/changed before the 1.6 s two-read gate finished |
| Static-screen anchor miss | 0 | no case where a settled frame was repeatedly unreadable |
| Reader disagreement as the root cause | 0 confirmed | the app did not log per-frame values; the failure was timing, not a logged vote conflict |
| Bonus panel not settled | 0 of the 7 | the 7 never reached a main-loot read, so no bonus decision was possible |

The seven incomplete records are one mechanism, not seven random OCR failures:

- detection succeeded, then the old code hid the overlay for 140 ms and took a
  “clean” screenshot while the result screen was still moving;
- it then required **two complete reads 800 ms apart** (plus reader time), i.e.
  ≥1.6 s, before saving;
- the result screen had already changed or closed by then. All seven log
  `loot did not stabilize` and were written as unreadable.

### Why the one record with all values still said `review`

Record `1793388007023` had main loot and bonus elixir/dark. The second frame read
bonus gold as **2,697,000** (one extra zero). The old stability gate compared
only gold/elixir/dark, so the wrong bonus was accepted; `LootSanity` then removed
it (above the 1,000,000 cap) and added the review reason. That is a real bad
value, but it should have been rejected by the episode, not repaired after
acceptance.

### A2/A3 — new episode capture

- One detected screen = **one episode**. Frames are polled at the target cadence
  (250 ms for the first two seconds, 500 ms afterwards), with the capture time
  scheduled rather than waiting for OCR to finish.
- The first screenshot is reused for its full-page OCR; subsequent frames read
  only the three loot rows and the bonus rows (balance rows are never read
  during capture).
- The main loot is accepted only after **two consecutive equal reads**.
- The bonus is accepted only after **three consecutive equal bonus reads while
  the bonus row boxes have stopped moving**. If no bonus panel appears within
  4 s of the main loot becoming stable, the record is written as “no bonus
  panel”. The episode hard limit is 12 s.
- The record is written **once**. If the screen closes, confirmed values are
  kept and the reason is `Closed early`; no second record is made.
- A 1.5 s cooldown follows the write, and OCR is skipped entirely while the
  recorded screen is still visible. A new capture starts only after the screen
  has been gone for three clear polls.
- The overlay is **never hidden**. On Android 14+ the reader captures only the
  game window (`takeScreenshotOfWindow`); if that fails it falls back to a
  display screenshot with the overlay regions masked. The method is logged on
  every detection/frame as `method=window`, `method=display+mask` or
  `method=display`.

### A4 — review policy and reasons

Review is now only produced for a real problem and the reason is stored on the
record and displayed on the dashboard row and in the record’s detail dialog:

- `Gold unreadable`, `Elixir unreadable`, `Dark elixir unreadable`
- `Loot unreadable` (no readable rows at all)
- `Loot not settled` (values changed through the episode)
- `Bonus missing` / `Bonus unreadable`
- `Closed early`
- plausibility reasons from `LootSanity`

A record with three main values and a bonus that was either read or confirmed
absent is **never** marked review.

Re-scan now re-reads a saved frame with all readers, applies the same sanity
rules and repairs a record only from a complete three-value read. The seven
existing incomplete records have no PNG (screenshot saving was off), so they
cannot be repaired from frames; new episodes have frames because
`Save results screenshots` is now enabled on the phone.

### A5 — what is not yet measured

The 20-real-attack test was **not run**; this is left to the manual pass. The
event log now records per-frame values and episode milestones (`firstComplete`,
`mainStable`, `bonusSettled`, `episode end`) so the capture/review/wrong-value
rates can be calculated directly.

---

## B. Recording strip (07a)

- New 44 dp strip: red dot with halo, tabular timer, live touch-counter chip,
  Restart, Cancel, Pause/Resume, Finish and Minimize.
- Width is 368 dp in landscape and 328 dp in portrait, capped to the safe area.
  The icon-only Finish variant is selected when the available width is short or
  the font scale is large; text auto-sizes and buttons shrink so nothing
  overflows at 80/100/130% controller scale.
- The counter increments once per recorded gesture (tap, drag or multi-touch),
  pulses for 120 ms, and is frozen while paused.
- Cancel/Restart with at least one gesture show the inline
  “Discard this recording?” row with Keep/Discard; it reverts after 3 s.
  Restart clears gestures, timer and counter and keeps recording.
- The minimized pill shows the timer and counter. The strip/pill footprint is
  the passive recorder’s exclusion rectangle, so taps on the strip are never
  recorded or counted.

---

## C. Dashboard macro library

- Import and Delete are real outlined 40 dp / radius 12 buttons with icons, on
  opposite ends of one row below “Macro library”.
- Delete enters selection mode: blue checkboxes, a pinned bar with
  “N selected”, Cancel and “Delete N”, a confirmation dialog, an **automatic
  timestamped backup file first**, and a 5-second Undo. A backup failure aborts
  the delete. Undo restores the exact macros in one repository write.
- The card play button is the shared 36 dp green circle/white triangle used by
  the floating menu.
- The card gear opens a bottom sheet with the same `MacroSettingsDraft` state as
  the floating settings: Name, Speed, Repeat/Count/Delay, Countdown, Stop
  conditions page, Pin to top, and Duplicate/Reset/Delete. Saving either
  surface produces the same `Macro`, because both call `draft.toMacro(macro)`.

Backups are written to `files/macro_backups/macros-<prefix>-<timestamp>.json`.

---

## D. Icon size and auto-dock

- The floating icon’s size is calculated from the natural display density and
  **bubble scale only**; `controllerScale` no longer touches it.
- New setting **Auto-dock icon when minimized**:
  - ON: minimize always docks at the chosen corner.
  - OFF: the icon appears at the panel logo, or at the last dragged icon
    position.
  - The corner picker is disabled while auto-dock is off.
- Changing auto-dock while minimized moves the icon immediately.
- Dock icon: 52 dp logo tile with a 2.5 dp state ring.

---

## E. Logo v2

- New vector glyph `ic_autoclash_glyph.xml` (crown, A, swoosh; gamepad and
  wordmark removed) on a new gradient squircle `bg_logo_squircle.xml`
  (30% radius, 1 dp inner highlight, no border).
- Glyph occupies ~70% of the tile.
- Used at 26 dp in the floating header, 52 dp for the dock icon (2.5 dp state
  ring), 40 dp in the dashboard header and 96 dp in About.

---

## F. Statistics

- Totals are now stacked under the resource name and use a fit-to-width text
  pass (down to 9–13 sp), so a 999,999,999,999 total cannot lose its first
  digit. The exact-value tile uses the same fit logic.
- 8 dp fade edges are drawn at the top and bottom of the dashboard and Settings
  scrolling bodies.

---

## G. Scroll performance

Root causes addressed in code:

- live statistics and probe updates no longer invalidate a per-row totals list;
- the dashboard’s “now” lambda is stable (`remember`), so the 15 s clock tick
  does not recreate every item lambda;
- the statistics scope list is memoized;
- `SimpleDateFormat` is cached per thread instead of allocated per row;
- per-row totals no longer allocate a `listOf(record)` and re-sum it;
- Compose strong-skipping is enabled;
- baseline profile + profileinstaller and R8 release build are present;
- the results watcher reads only loot/bonus rows and skips OCR while a recorded
  screen is still visible; the old 1 s full-page polling loop is gone.

Added `benchmark/` with:

- `ScrollBenchmark` — Macrobenchmark `FrameTimingMetric` for dashboard and
  Settings scrolling;
- `FrameThresholdTest` — on-device gfxinfo gate: **janky frames < 2%** and
  **95th percentile < 10 ms**;
- `BaselineProfileGenerator` — regenerates the baseline profile.

**No before/after frame numbers are reported yet**; the manual pass has not run
the benchmark. Run:

```
./gradlew :benchmark:connectedBenchmarkAndroidTest \
  -Pandroid.testInstrumentationRunnerArguments.class=com.clahbot.benchmark.ScrollBenchmark
./gradlew :benchmark:connectedBenchmarkAndroidTest \
  -Pandroid.testInstrumentationRunnerArguments.class=com.clahbot.benchmark.FrameThresholdTest
```

---

## Verification performed here

- 208 app unit tests + 50 OCR-lab tests: all pass (2 app tests skipped).
- `:app:lintDebug`: 0 errors, 137 warnings.
- `:app:assembleDebug`: success.
- `:app:assembleRelease` with R8/shrinking: success on the final source.
- `:benchmark:compileDebugKotlin`: success.
- Installed 2.8.1 in place; verified `versionCode=24`, `versionName=2.8.1`.
- App data was backed up to `.ocr-work/backups/20261030_polish/` before the
  install; the final post-install copy is in `final/` (macros `aeb3dcfd…`,
  attacks `ebc784e0…`, sessions `2550b16b…`). The live files changed during the
  session because the user used the app (macros, attacks and sessions grew);
  the app did not restore or overwrite the user’s data. `Save results
  screenshots` is enabled for the manual run.

## Manual acceptance still to do

1. Run 20 real attacks and pull `files/autoclash_events.log` plus
   `files/results_captures`; compute capture/review/wrong-value counts and
   confirm the logged capture method (`window` should be used on this phone).
2. Check the strip at 368/328 widths and 80/100/130% controller scale, including
   font scale 1.3; verify Restart/Cancel confirms, counter freeze and pulse.
3. Import / Delete / selection / backup / 5 s Undo.
4. Confirm the dashboard play button and the bottom-sheet fields match the
   floating menu, and that a Save in either place produces the same macro.
5. Verify icon size is unchanged when controller scale changes, then test
   4 corners × 2 orientations × auto-dock on/off.
6. Compare the dashboard, library, settings sheet, strip and logo with the 07a–07d
   PNGs.
7. Run the benchmark/threshold tests and record before/after jank and p95.
