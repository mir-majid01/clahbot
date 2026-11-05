# AutoClash 2.12.0 — recorded-macro walls, loot capture fix

Date: 2026-11-03  
Release: **2.12.0 (versionCode 33)** — built by GitHub Actions, numbers appended
below once the run finishes.

The wall-line upgrader from 2.11.0 is gone. Walls are now driven only by a
macro the user recorded themselves, and the loot pipeline writes its record the
moment the numbers stop moving.

## 1. What was removed (deleted, not hidden)

| File | It used to |
|---|---|
| `walls/WallUpgrader.kt` | run the generated A→B tap list |
| `walls/WallMath.kt` | interpolate wall coordinates along the line |
| `walls/WallBarReader.kt` | OCR the wall level bar |
| `walls/WallDialogParser.kt` | parse the upgrade dialog / Wall Ring prices |
| `walls/CameraAligner.kt` | landmark patch + camera drag alignment |
| `overlay/WallLineView.kt` | draw the line, A/B markers, drag handles |
| `res/layout/overlay_wall_setup.xml` | the line-calibration panel |
| `res/drawable/bg_marker_a.xml`, `bg_marker_b.xml`, `bg_setup_panel.xml` | marker and panel art |
| `WallMathTest`, `WallDialogParserTest`, `WallBarReaderTest` | tests for the above |

`WallSettings` lost `line`, `camera`, `cameraMacroId`, `goldCost`/`elixirCost`,
`goldEnabled`/`elixirEnabled`, `reserveGold`/`reserveElixir`, the two
thresholds, `maxPerCycle`, `wallLevel`, `capSession`, `capDay` and
`FULLER_FIRST`. The camera macro selector, landmark marking, wall-count and
"already at max" maths, per-level progress bars and the wall-level/priority
controls are all gone. A grep for the deleted class names returns nothing
outside this report.

Kept, because they are general machinery: `AppResultsReader`, the `ocr` module,
`MacroPlayer`, `GameInput`, the accessibility service and gesture dispatch, the
screen-state engine, and the recovery watchdog.

## 2. What replaced it

- `walls/WallModels.kt` — one `WallSettings`: `enabled`, `wallMacroId`,
  `resourceMode` (Gold / Elixir / Both), `priority` (Gold first / Elixir first),
  one `costPerWall` covering both resources, `minReserve`,
  `allowRepeatUpgrades`, `testedWallMacroId`, `history` (last 50 runs).
- `walls/WallLogic.kt` — the whole decision and safety layer as pure functions:
  `spendable`, `canAfford`, `choose`, `stable`, `stableReadings`, `safeToStart`,
  `unsafeTaps`, `unsafeDuringRun`, `safeAfterRun`.
- `walls/WallAutomation.kt` — `Mode.AUTOMATIC` / `MANUAL` / `TEST` over one
  pipeline: wait for a settled home village → resolve the macro → gates →
  two balance reads → choose the resource → refuse unsafe taps → replay the
  recorded gestures with screen checkpoints → verify the village is safe →
  log.
- `walls/WallsRepository.kt` — `walls.json`, atomic `.tmp` + rename, defensive
  decoder, `StateFlow` so both surfaces move together.
- `res/layout/overlay_wall_picker.xml` — the macro picker as an overlay window,
  never an Activity.

Trigger: `MacroPlayer.play(..., onLoopBoundary)` calls
`FloatingMenuService.wallLoopBoundary()` in the gap **between** attack
repetitions, which is the only moment the village is back and no gesture stream
is active. `WallAutomation.onHomeVillage` then requires Auto Walls to be on,
one upgrade per home visit unless repeat upgrades are allowed and a 20 s
cooldown has passed.

One visit, one wall: `startPlayback` calls `noteLeftHome()`, so each boundary is
a fresh visit. A second upgrade inside the **same** visit needs
`allowRepeatUpgrades`, a balance drop proving the first one actually spent, and
fewer than 5 repeats done — `WallLogic.mayRepeatUpgrade` is that rule as one
pure function, so a stuck OCR reading cannot drain the storeroom.

## 3. Wall macros and per-attack linking

- `Macro.kind` (`attack` | `wall`) and `Macro.linkedWallMacroId`. Old files
  without the fields load as attack macros with no link.
- "Record wall macro" uses the same passive recorder; the save dialog defaults
  to "Wall upgrade" and the result is stored with `kind = wall`.
- Wall macros never enter the attack picker, the attack loop, the library list
  or the dashboard macro rows — they appear only in the Walls tab, the dashboard
  Walls card and the per-attack link sheet, so a wall run cannot add an attack
  statistic.
- Rename, delete, select and re-record are available from the Walls tab and the
  dashboard; the dashboard row shows `12 gestures · 8.4 s` for inspection.
- Resolution order: the attack's linked macro, else the global Walls macro. A
  linked or selected macro that no longer exists is a clear error
  ("The wall macro linked to "TH farm" is gone. Re-link it or pick one in
  Walls.") and the upgrade is skipped — never a silent substitution. At a loop
  boundary that message goes to the event log and the Walls status line instead
  of a toast on every repetition.
- A freshly recorded macro is never spent automatically: only a **Run now**
  that plays to the end sets `testedWallMacroId`. **Test macro** is a dry run —
  it reads both counters, shows the numbers, the cost, the reserve and which
  macro would run, and taps nothing; it does not mark anything as tested.
  Changing the selected macro clears the tested marker.

## 4. Gem safety

- `WallLogic.unsafeTaps` checks every recorded tap of the macro against the
  current frame's never-press boxes before a single gesture is dispatched; one
  hit means the run is refused and logged.
- During the replay, every 4 gestures (at least 1.2 s apart) the screen is
  re-read: POPUP, UNKNOWN, NOT_CLASH or BATTLE aborts the run immediately.
- After the replay the village must be verified safe (HOME / ATTACK_MENU /
  SEARCHING) before the attack loop is allowed to resume; otherwise
  `safeToResume = false` and the service stops the loop.
- Prices still come only from `BitmapIconColorSampler`, so a gem/Wall Ring price
  can never be classified as a wall cost; recovery actions are born inside
  `SafeActionFactory`, which refuses any target intersecting a forbidden box.
- Safety outranks completion: every stop path records a reason.

## 5. Loot detection and statistics

Root cause (measured in the code, not guessed): the record was written **only
when the whole episode closed**, and an episode closed only after the bonus
panel resolved or the 12 s cap. Three things made loot look "slow or never
recorded":

1. `EpisodePolicy.acceptMain` gated stability on `read.lootComplete`, which
   requires a dark-elixir row. On TH 13+ many results screens have no dark row,
   so main loot never became stable and the record waited for the 12 s cap.
2. `sameMain` compared dark elixir strictly, so a frame where the dark row
   flickered in or out reset stability and the two-frame requirement restarted.
3. The save itself happened after the episode, so statistics and the dashboard
   refreshed seconds after the numbers were plainly readable. A 10-minute
   duplicate window then discarded later legitimate results whose screen hash
   collided.

Fix: `ResultsParser.mainLootComplete` (gold + elixir only) gates stability;
`sameMain` tolerates a dark value present in one frame; dark becomes a
`partialReasons` entry ("Dark elixir unreadable — gold and elixir still count")
instead of a review blocker; `stableJustArrived()` lets the coordinator save the
record the moment the two stable frames land and `update` the same row when the
bonus settles; the duplicate window is 45 s with the reason written down. The
results screen is still held for the user's 3–4 s — nothing was shortened.

## 6. Migration

`walls.json` from a wall-line release is recognised by its obsolete keys and
ignored wholesale rather than half-migrated; malformed or truncated JSON, an
unknown enum value, a missing field, or history rows from the old
`WallCycleLog` shape all fall back to defaults with no exception. Absent fields
take the Kotlin defaults (the decoder seeds from a serialized default object,
because Gson would otherwise put `null` in a non-null enum). `attacks.json` and
`macros.json` are not touched by this migration, and Auto Walls off leaves the
attack path exactly as it was.

## 7. Verification

`./gradlew --no-daemon --stacktrace testDebugUnitTest` — the same command CI
runs, unfiltered, on the whole repo:

- app + ocrlab unit tests: **361 tests, 0 failures, 2 skipped**
  (311 app + 50 ocrlab; the 2 skips are pre-existing `@Ignore`s unrelated to
  this batch).
- Measured in `LootCycleSimulationTest` (simulated frames, real file I/O):
  10 result cycles → **10 detections, 10 records, 0 duplicates, 0 misses**.
  Worst detection latency **500 ms** after the first readable frame (two 250 ms
  polls), worst record write **7 ms**, and the `StateFlow` the dashboard
  collects already contains the row when the write returns — asserted per cycle
  by `the dashboard source already holds the row when the write returns`.
  Before this batch the same screen waited for the bonus panel or the 12 s
  episode cap.
- New in this batch: `WallLogicTest` 21 (reserve example from the spec,
  per-mode choices, priority tie-break, stability tolerances, tap-on-gem
  refusal, screen gating, the same-visit repeat rule and its ceiling),
  `WallsRepositoryTest` 11 (old wall-line file,
  truncated JSON, unknown enum, stale history rows, bounded history, failed
  write keeps the good file, summary ordering, amount formatting),
  `LootCycleSimulationTest` 8 (the ten cycles above, the late-bonus update,
  missing-dark gap, explicit miss, duplicate, non-duplicate after the window,
  immediate refresh, totals survive a reopen).
- Updated: `EpisodePolicyTest` — the test that pinned the old contract ("a
  missing dark row blocks the record") was rewritten to the new one, and a
  missing-gold case was added so the blocker still exists where it belongs.
  `SafeActionFactoryTest` 12, `GameScreenEngineTest` 11 and
  `BitmapIconColorSamplerTest` 5 still pass, which is what keeps the gem
  refusal structural.
- Guard check: the never-press refusal is proved by a test that asserts
  `SafeActionFactory` returns no action and reports why, and
  `WallLogicTest` asserts a macro whose tap lands inside a forbidden box is
  refused before any gesture is dispatched.

Spec §10 matrix status:

| Area | Covered by | Device? |
|---|---|---|
| Wall automation (10 cases) | `WallLogicTest`, `WallsRepositoryTest`, code path review | not run |
| Loot (8 cases) | `EpisodePolicyTest`, `LootCycleSimulationTest`, `CaptureCycleTrackerTest`, `ResultCaptureGateTest` | not run |
| UI (7 cases) | compile + layout id cross-check (every `wall*` id in the layout is bound in code; no dead wall control remains) | not run |
| Regression (6 cases) | full unit suite green | not run |

## 8. Honest gaps

- **No device pass.** `adb devices` listed nothing (USB and mDNS), so no APK was
  installed and no real screen was OCR'd or tapped. Everything above is
  simulated/unit evidence. The untested parts are exactly the parts that need
  hardware: real top-bar OCR reads on the home village, the recorded macro
  landing where it was recorded, popup behaviour mid-run, the wall strip on the
  Infinix, and 10 real result screens for the loot timings.
- `LootCycleSimulationTest` measures the policy→record→file pipeline. The
  on-device "UI refresh" leg (StateFlow → Compose re-render) and the true OCR
  latency per frame are not measured here.
- Wall macros show a summary (gesture count, duration) in the Walls card, not the
  per-gesture timeline the attack sheet has.
- Auto Walls upgrades in the gap between repetitions of a running loop. After the
  final repetition of a finite loop nothing is scheduled, by design: §3 gates the
  workflow on the attack automation still running, and the village-only wait
  would otherwise race the dashboard auto-open. Use Run now for that last attack.

# AutoClash 2.11.0 — smart recovery + auto wall upgrader

Date: 2026-11-02  
Release: **2.11.0 (versionCode 32)**  
GitHub release: <https://github.com/mir-majid01/clahbot/releases/tag/v32>  
GitHub Actions run `41592266222` succeeded.  
CI-built APK: `AutoClash-2.11.0.apk` (51,157,274 bytes)  
SHA-256: `eae58f092029d914246aa7c71514941ab6d953f7ba8fe5178bb5d8bad9ae0844`

The release was built and signed by **GitHub Actions**, not on this machine. The
APK was downloaded here and its SHA-256 matches the asset; `aapt` reads
`versionCode=32`, `versionName=2.11.0`. The first v32 tag was cancelled before
anything was published because a Live-mode price check was tightened; the
published tag points at commit `2fd73ee`.

## What this batch adds

### 1. Game screen-state engine (`com.clahbot.game`)

- `GameScreenEngine.inspect(bitmap, OcrPage, foregroundPackage)` classifies
  NOT_CLASH, POPUP(kind), BATTLE, RESULTS, SEARCHING, ATTACK_MENU, HOME,
  LOADING and UNKNOWN in that order. Text anchors are stored in screen
  fractions, so one engine serves any resolution.
- Colour anchors come from a 240×135 downscaled flood fill: squarish red X
  (1.2–6% width), wide green confirm (≥8% width, aspect ≥1.4), gem icons
  (small bright green blobs) and a dimmed scrim.
- Every pass returns state, confidence, popup kind, close/confirm boxes, safe
  targets, forbidden boxes, text lines, frame size, average hash, mean value
  and saturation.

### 2. Smart recovery (`com.clahbot.recovery`)

- `RecoveryWatchdog` polls at 1 Hz during every macro run and re-observes at
  800 ms inside a recovery attempt.
- Popup grace 6 s → `RecoveryAgent.clearPopup` (3 tries, 800 ms apart) with
  the amber strip “Closing a popup… try N of 3”. Success resumes the player;
  failure stops the run and marks it interrupted.
- Limits: 3 recoveries per loop and 8 per hour. At either limit the run stops,
  the reason is logged and a screenshot is saved to the Unknown gallery.
- NOT_CLASH: BACK once, launch Clash, wait up to 20 s. Screen off or locked
  stops immediately. A frozen frame (identical hash for 20 s, outside
  searching/results/loading) gets 2 clear attempts and then stops.
- Unknown screens are saved after 15 s, capped at 20 screenshots. The
  dashboard gallery offers Teach: tap the spot, name it, choose Tap here /
  Close ✕ / Wait / Go home. Taught rules match by image hash (distance ≤ 3).
- After 3 clean loops (RESULTS → HOME/ATTACK_MENU) the watchdog logs the
  learned sequence.
- Built-in rules: connection lost → Reload; Surrender?/Exit? → Cancel; gem
  dialog → red X only; shop/offer/rate/season → red X.

### The never-press list is code, not a promise in a rule file

`SafeActionFactory` is the only place recovery actions are born. Every target
is checked against the observation's forbidden boxes (every gem blob expanded,
plus Buy / Surrender / End Battle / Quit / Exit / Charge / Top up / Gems
captions). On a gem-spend dialog every caption action is refused and only the
red X is proposed. Tests that fail if this ever breaks:

- `SafeActionFactoryTest.no candidate action ever intersects a forbidden box`
  enumerates every built-in rule and the generic chain.
- `SafeActionFactoryTest.an action aimed at a gem button is refused by the factory`.
- `SafeActionFactoryTest.gem dialog refuses every caption action`.
- `BitmapIconColorSamplerTest.wall ring blue is never classified` — a blue
  Wall Ring can never become a gold/elixir price at all.

### 3. Auto wall upgrader (`com.clahbot.walls`)

- Walls tab in the controller: on/off, line progress “34 / 120 walls”, cost per
  wall for gold and elixir with per-resource switches, Detect price, reserves
  and run-when thresholds (1M steps), spend caps (session/day), priority
  (gold first / elixir first / fuller first), max per cycle, current wall
  level, camera macro picker, landmark marking and cycle history.
- 10b setup overlay: full-screen and only present while setting up. Green A
  and red B markers are dragged over the captured frame, tap dots are drawn
  along the line, total walls and already-at-max are steppers, Cancel / Save.
- Landmark: drag one marker to a stable patch near the walls and save it. The
  patch is template-matched every cycle (coarse ¼-scale NCC, full-res
  refine, 24 px tolerance, one retry through the camera macro).
- Cycle: balances consensus read → N = floor((balance − reserve)/cost)
  clamped by max per cycle and walls remaining → camera macro + alignment →
  tap the wall at the line position and verify “Wall (Level X)” (then ±1..3
  neighbours) → Upgrade More, +10 × floor(N/10), +1 × N mod 10 with count
  verification → read the gold/elixir price and compare to the setting (3%)
  → tap Upgrade → read the LARGE price in the confirm dialog (the small
  struck-through original is ignored) → Test closes the dialog with X and
  logs “would have spent”; Live confirms → verify the balance dropped by
  about the price → advance progress and write the cycle log.
- Refusals: no line, no level, not on HOME, macro running, price >3% higher,
  balance not verified, unreadable confirm price in Live mode, gem or
  insufficient dialog (close with X, mark insufficient), spend caps reached,
  no Upgrade buttons — each stops the cycle and reports why.
- Stop always wins: the stop button cancels the job and closes an open dialog
  with X or Cancel.
- The blue Wall Ring button is structurally unbuyable: prices only come from
  `BitmapIconColorSampler`, which never returns blue.

### 4. UI (10a–10d)

- Controller tabs Macros | Stats | Walls.
- Blue wall strip “Walls · Gold · 8 of 40” with the same 52 dp size and stop
  button as the playback strip; the amber recovery strip swaps in during a
  recovery; result toasts auto-dismiss after 5 s.
- Dashboard Automation card: Recovered / Stopped / Unknown counters and the
  Unknown screens gallery with Teach.
- Dashboard Walls card reads the same `walls.json` state as the overlay.
- None of the overlay wall/recovery flows starts an Activity; setup and
  pickers are overlay windows.

## Verification

- `./gradlew testDebugUnitTest` (all modules): **313 app tests, 2 skipped,
  0 failures; 50 ocrlab tests, 0 failures**. 71 new tests: WallMathTest 28,
  WallDialogParserTest 7, WallBarReaderTest 8, SafeActionFactoryTest 12,
  GameScreenEngineTest 11, BitmapIconColorSamplerTest 5.
- `./gradlew :app:lintDebug`: **0 errors, 239 warnings** (pre-existing
  hardcoded-string and deprecation warnings).
- The engine fixtures cover all nine classes with synthetic bitmaps + OCR
  pages, including the gem-dialog forbidden box and the red-X close button.
- Wall-bar fixtures cover the selection caption, Upgrade vs Upgrade More,
  +10 / +1 exact matching, the x-count chip, gold-only pricing, the blue ring
  refusal and the bar-band cutoff.

## Honest gaps and deviations

- The screenshot folder in the request was a literal placeholder
  (`[FOLDER PATH]`), so a per-class accuracy table on your labelled screenshots
  could not be produced. The engine ships with the fixture suite above, and the
  device pass records the real transitions it observed (the watchdog logs
  state and confidence).
- On a failed recovery the run STOPS and is marked interrupted instead of
  resuming into a possibly desynchronised gesture stream. This is a deliberate
  safety choice.
- “Run when” triggers check the balances once a minute while the game is on
  HOME and no other run is active.
- Until a landmark is marked and a walls camera macro is picked, the cycle
  stops with “Camera alignment failed” — there is no fallback to blind taps.
- Wall-flow screenshots from your phone were not available here; the device
  pass captures the wall bar, confirm dialog and gem dialog for the record.

## Device pass (2.11.0)

**Not run yet.** The phone was not connected over adb during this session
(`adb devices` empty), so nothing was installed or tapped. The verified APK is
ready at `.release/AutoClash-2.11.0.apk`. The pass, when the phone is connected:

- [ ] `adb install -r` (data preserved) and re-enable accessibility
- [ ] Test (no spend) cycle with logs
- [ ] Live cycle N=1
- [ ] Loop with an injected popup

---

# AutoClash 2.10.5 — v6 batch and follow-up report

Date: 2026-11-02  
Release: **2.10.5 (versionCode 31)**  
GitHub release: <https://github.com/mir-majid01/clahbot/releases/tag/v31>  
GitHub Actions run `41508904248` succeeded.  
CI-built APK: `AutoClash-2.10.5.apk` (51,075,733 bytes)  
SHA-256: `40a6ab430ae8975f1c17746ce1f43e0296972faf74f416195c324db0245d42b9`

The release was built and signed by **GitHub Actions**, not on this machine.
It was installed in place over 2.10.4 on the Infinix X6876; the installed base
APK hash matches the asset above, `versionCode=31`, `versionName=2.10.5`, and
app data was preserved.

Automated checks: **242 app unit tests, 2 skipped, 0 failures**; lint
**0 errors**.

## 2.10.5 playback-strip name and stale save name

- Playback strip name: the name, run chip and timer shared one row, so at the
  368 dp landscape width the name received only ~76 dp and was cut to
  “Macro Nov…”. The strip is now two lines inside the same 52 dp height: the
  macro name owns the full text column (~188 dp landscape, ~148 dp portrait),
  with the elapsed timer and run progress underneath. The run text is hidden
  for a single run instead of showing the meaningless “Run 1/1”, and the timer
  and run are plain text rather than a chip competing with the name.
- Macro save name: `reviewName` survived after a macro was saved, and the next
  recording’s review panel prefilled that old name; the 8 s auto-save then
  stored the new recording under the previous name. Every new review now
  generates a fresh default name, and the name is cleared when the review
  closes. Default names also get “ 2”, “ 3” suffixes if the same minute’s name
  already exists.
- Tests: `PlaybackStripLayoutTest` now checks the name column width, the
  two-line geometry and portrait width, and renders a 3× preview to
  `app/build/reports/playback-strip/landscape-368.png` for visual inspection.
  `MacroNamingTest` covers unique default names. 242 app tests pass (2 skipped);
  lint 0 errors.
- The interactive device pass could not be completed this round because the
  phone was in active use; the installed build and the rendered preview were
  verified. The live playback-strip screenshot and a real two-recording save
  are left for the next free-device pass.

## 2.10.4 updater rate limit, notification and install flow

- Update checks now use the GitHub **Releases Atom feed** first. It is not part
  of the REST API and is not subject to the anonymous 60-requests/hour quota,
  so repeated checks no longer show “GitHub rate limit reached.” The REST API
  is only a fallback, with a friendly “GitHub is busy” message.
- The Settings → App update card is now complete: it shows the available
  release, downloads the APK with SHA-256 verification, and opens the Android
  installer. It explains that Android may ask to allow installs from this
  source, then the APK confirmation.
- `REQUEST_INSTALL_PACKAGES` is declared again because a self-updater cannot
  open the package installer reliably without it. The release is still
  non-debuggable and signed with the stable key.
- The app checks for updates on launch (at most every 6 hours) and posts one
  notification per new version. Tapping the notification opens the App tab,
  checks again, downloads, and launches the installer automatically.
- Added an Atom parser test. The 2.10.3 → 2.10.4 end-to-end notification tap
  could not be exercised by downgrading the phone because Android rejected the
  test downgrade; the final 2.10.4 release is installed and the next published
  version will exercise it normally.
- 234 app tests pass (2 skipped); lint 0 errors.

## 2.10.3 session totals, strip placement and minimize defaults

- Floating session totals: the saved result was attached to the active session
  explicitly (`syncWithRecords(includeIds)`), and Session scope now also
  includes confirmed attacks that started during the still-active session even
  if the session JSON has not caught up yet. The loot tiles and Recent list now
  move together. Regression test:
  `aRecordThatStartedBeforeTheSessionIsAttachedWhenExplicitlyIncluded`.
- Playback strip position: the default is now the recording strip position
  (centred at the top), or the user’s dragged recording-strip position. It is
  no longer chosen from the macro’s touch points or docked to the right corner.
- Strip corners on other OEMs: all controller/strip clamping now keeps an 8 dp
  screen margin, so a panel cannot sit flush against a display edge and render
  one flattened corner. We could not reproduce the Realme model here; the
  centered placement and margin are the targeted fixes.
- New Settings → Controller defaults:
  - **Start recording strip minimized**
  - **Start playback strip minimized**
  Both persist and make the corresponding strip open as the compact pill.
- Saving a recording now returns to the main floating controller after **2 s**
  instead of 5 s.
- 233 app tests pass (2 skipped); lint 0 errors. The 20-real-attack loot run
  and a Realme hardware pass are still pending.

## 2.10.2 countdown and strip follow-up

The red line was the old `countdownPanel`: during playback the controller
window is only strip-height, so its large “5 / Switch to Clash / Stop” panel was
clipped into a red horizontal line (the Stop button) and the status text was
drawn loose over whatever app was in front.

- Deleted the separate countdown panel and all of its references.
- The green strip is entered immediately when PLAYING starts. Its timer shows
  the countdown in amber (`3s`) with an accessibility description, the run chip
  is hidden until the countdown ends, and Pause is disabled/dimmed during the
  countdown. Stop and Minimize still work.
- The strip is now 52 dp high instead of 44 dp, with 36 dp pause/stop/minimize
  buttons and 8 dp gaps (6 dp at narrow width), so the buttons no longer touch
  the edges.
- The name is a weighted, ellipsized field, so the layout has no fixed
  `maxWidth` that clipped names; the strip width/height never shrink below the
  unscaled design size when the controller is set to 80 %.
- Fixed a remaining race where the “start minimized” collapse animation finished
  after playback began and replaced the strip with the bubble. Playback now
  cancels the collapse, and the animation refuses to collapse while playback,
  recording or review chrome is active.
- The standalone status line is hidden whenever any custom chrome is active, so
  “Starting in 3s” and similar text cannot float outside the strip.
- Added `PlaybackStripLayoutTest` (Robolectric) asserting 36 dp controls, at
  least 6 dp vertical breathing room, no overlap and a 46 dp timer.

Verified on the release with a cold-start dashboard Play: the strip appears
immediately, shows `3s` in amber, has evenly spaced controls, and the red line
and loose status text are gone.

## 2.10.1 follow-up fixes

### Splash

The old splash stretched the 900×780 mark into a square and cropped its dark
edges. A generated 512×512 tile now keeps a 26 px transparent margin, clips the
art to a smooth squircle and preserves its aspect ratio; the drawable is
`gravity="fill"` only because the tile itself is square. Verified with a
cold-start capture on the phone (`release-splash.png`): the old hard square is
gone, replaced by a centred rounded app tile on the dark background.

### Playback strip

Two separate root causes were reproduced:

1. `placeControllerForPlayback` used to set `collapsed=true`, hide the panel and
   show only the bubble *before* playback. `enterPlaybackChrome` then changed
   the strip size but never made the panel visible or hid the bubble, so the
   user saw the icon until tapping it.
2. After minimize/reopen the `contentFrame` still had its 4 dp controller top
   margin and the window was clamped against the old 32 dp pill height, which
   could cut the expanded 44 dp strip.

The strip position is now chosen before play and stored; `refreshUi` enters the
chrome only once the player is actually PLAYING, makes the panel visible, hides
the bubble, removes the content margin, makes the playing row fill the 44 dp
panel, forces WRAP_CONTENT window params, and re-clamps after layout. The strip
now appears immediately after play, and minimize → pill → reopen produces the
full-height strip. Playback that stops because Clash left the foreground now
sets READY, ends the capture session and restores the controller instead of
leaving the strip frozen.

### Accessibility state

Device evidence showed the exact confusing state: `enabled_accessibility_services`
listed AutoClash while `accessibility_enabled` was `0`, and the settings list
could still show “On”. The old boolean treated this as a binary permission.
There are now three states — disabled, enabled-not-connected, connected — with
a resume/4 s refresh. The dashboard and Settings show “Not connected” plus
“Open Accessibility, turn AutoClash off, then on”, and explain Android’s
“Allow restricted settings” path with an App info button. Service startup is
wrapped so a session-tracker failure cannot crash the accessibility service;
connect/unbind/destroy are logged.

On the phone: after `am force-stop` Android cleared the accessibility master
(so the app correctly showed Enable); enabling the service showed the standard
Android “Allow AutoClash to have full control?” consent, and after Allow the
system reported a Bound service and the app showed Connected. That consent
dialog is an Android security prompt and cannot be removed by the app.

### Trust / installation warnings

- Final APK is the **R8-minified, non-debuggable release** signed with the
  stable release key, not the debug APK used for iteration.
- Removed the unused `REQUEST_IGNORE_BATTERY_OPTIMIZATIONS` and
  `REQUEST_INSTALL_PACKAGES` permissions. In-app updates now open the system
  package installer with `ACTION_VIEW`; the app no longer asks for the
  “install unknown apps” permission itself.
- Added `usesCleartextTraffic="false"` and a local-only disclosure in the
  permission card/Settings.
- Play Protect/Antivirus may still warn about a sideloaded automation app, and
  Android always shows the accessibility consent; those are OS security
  behaviours, not something an app can silently bypass.

### Scroll performance

The slow frames were UI-thread composition (10–36 ms), not GPU. Statistics was
one monolithic LazyColumn item; it is now split into header, banner, totals,
session header, each session box, and the history link as separate lazy items.
The 15 s dashboard clock tick became 60 s because session cards show whole
minutes.

Release measurements on the X6876 (`dumpsys gfxinfo`):

- Dashboard: 682 frames, **0.15 % janky**, legacy 1.17 %, p50 10 ms,
  **p95 11 ms**, p99 13 ms.
- Settings: 670 frames, **0 % janky**, p50 9 ms, **p95 10 ms**, p99 12 ms.

Debug warm measurements before the split were 1.36 % jank / 44 ms p95; the
release is the number that matters and is now within the 10 ms p95 class on
this phone.

### Other UI

- Dashboard pinned macros now show the pin icon instead of the literal
  `PIN ·` prefix.
- Notification recording counts use Android plurals.

## Previous 2.10.0 evidence (retained)

The sections below document the earlier v6 batch. The current release includes
those changes plus the follow-up fixes above.

### Device status

The 2.10.1 release was installed in place on the Infinix X6876; app data is
preserved by `adb install -r`. The 20-real-attack loot measurement and the full
launcher/notification screenshot set are still pending manual testing and are
not claimed as passed.

Reference 09c/09d were compared as images. The `autoclash-logo-pack-v2.zip`
logo pack is not present on disk (`Desktop/refpack-logo2` is empty), so the
original artwork already committed to the repository was restored instead.

---

## 1. Logo and brand

- Restored the **original** artwork from git and deleted the redrawn vector
  marks: `ic_autoclash_full.png`, `ic_autoclash_mark.png`,
  `ic_launcher_art.png`, original `ic_notification.xml`,
  `ic_launcher_foreground.xml`, `ic_launcher_monochrome.xml`,
  `ic_splash_logo.xml` and the adaptive mipmaps. There are no `ic_brand_*` or
  `bg_logo_*` references left.
- Floating controller header logo: **26 dp**. Dock: **52 dp visual** (touch
  target padded to at least 48 dp), tile at **85 %**, no hard border.
- Native outline: smooth squircle with a **30 % corner radius** applied through
  `ViewOutlineProvider` + `clipToOutline`; ring stroke is **4.5 %** of the
  visual, with a 3 %-class gap, green when ready, red with a soft 700 ms pulse
  while recording, amber when paused.
- Dashboard header uses the original mark; Settings → App uses the original
  full wordmark at 132 dp; launcher normal/round/themed, splash and notification
  use the original foreground/background/monochrome assets.
- *Device pending:* launcher normal/round/themed, splash, notification-shade and
  dock screenshots.

## 2. Saved-recording state bug

Root cause: `scheduleReviewDismiss` cleared `reviewChromeActive` before calling
`restoreControllerChrome`, whose first guard then returned early. The review
box stayed hidden, the red Record button remained and status text leaked.

Fix: the dismiss timer now restores the controller first; the restore path
recreates the header, content, footer, panel background/padding, page state and
the saved window position. Review mode hides `tvStatus`, so “Reading
touchscreen…” cannot appear outside its component. The saved toast dismisses
after **5 s**, and only the one controller window is used (inline cards are
children of it, never separate windows).

Code paths covered: record → finish → Save; record → finish → Discard; record →
finish → wait 8 s for auto-save. *Device pending:* the three visual flows.

## 3. Playing strip (09b)

- One **44 dp row**: play dot, ellipsized macro name, run chip
  (`Run 1/3` / `Loop 12`) with `singleLine`, tabular timer with a 46 dp
  minimum, Pause, Stop, Minimize, equal 6 dp gaps (4 dp at the narrow width).
- No caption or status text outside the strip; no bottom clip; timer has
  `maxLines=1`/`singleLine`.
- Recording strip layout is unchanged from the approved 07a implementation.
- Narrow/80 % width copies `tvPlayElapsed` at 11.5 sp inside 46 dp; the name
  shrinks to 58 dp. *Device pending:* screenshots at 80/100/130 % and long names.

## 4. Floating macro list (09a)

Boxed cards with a 1 dp `#22304D` border, 12 dp radius, 6 dp gap, blue selected
tint/border; pinned macros use the pin icon (no “PIN ·” text); play is a 30 dp
green circle; gear is a plain icon. *Device pending:* visual screenshot.

## 5. Floating UI must never leave the game

Static audit of every launch in `FloatingMenuService`:

| Call | Previous behaviour | Now |
|---|---|---|
| `btnSeeHistory` | Started `MainActivity` immediately | Inline “Open AutoClash? This leaves Clash of Clans.” card with Stay / Open app |
| `btnOpenSettings` | Started `SettingsActivity` immediately | Same explicit inline confirmation |
| Recording-access failure (Shizuku/root) | Started `SettingsActivity` automatically | Inline confirmation before Settings |
| Text fields (name/numbers) | `MacroInputActivity` | Inline `EditText` card inside the overlay |
| Confirmations | System `Dialog` | Inline card; no system dialog remains |
| Notification content `PendingIntent` | — | Only fires when the user taps the notification |

The surprise redirect was caused by the old direct `MainActivity` launch from
See all history; the other two implicit launches were the text editor and the
Shizuku failure path. All three now require the explicit Open app button.

## 6. History and Statistics (09c / 09d)

- New full-screen History: back + title; dismissible amber “N attacks need
  review” banner with Re-scan and X; All / Needs review / This week chips;
  collapsible session boxes with “Nov 01 · 11:46”, duration · attacks, three
  totals, status chip and chevron.
- Expanding a box shows every attack (time, duration, three totals, reason
  chip). Tapping an attack opens a bottom sheet with details and Edit/Delete;
  there are no inline edit/delete icons.
- Dashboard Statistics now uses the same session boxes, followed by
  “See all history”, which opens the full History page (including the floating
  overlay deep link).
- One definition everywhere: `AttackRecord.needsReviewDefinition` drives the
  banner count, stats count, session totals and history filter. “Unreadable”
  and “incomplete” are no longer separate counts.
- Plural audit: `Plural` is used for attacks, macros, sessions, records, results,
  gestures, times, unreadable-session copy and deleted-item copy. Tests:
  `PluralTest`, `HistoryFilteringTest` (covering the week boundary, long
  sessions with a recent attack, review sessions and orphan attacks).
- *Device pending:* expand/collapse, banner dismiss/rescan and attack-sheet
  screenshots.

## 7. Loot numbers never clip

Actual root cause: the floating total used an auto-sizing `TextView` with
`wrap_content` inside a weighted row. Measuring that view with `UNSPECIFIED`
left TextView’s internal `Layout` **1,048,576 px wide**; with `gravity=END` the
text was right-aligned inside that huge layout and drawn outside the actual
view, so only the left digits disappeared. A Robolectric probe printed
`layout=1048576 measured=8` for a one-character total.

Fix in `LootRowLayout`:

- the exact total width comes from `Paint.measureText` (single line, tabular
  digits), then the total is measured with an **exact** width;
- the view is placed at the right edge but draws with `gravity=START`, so the
  first digit can never be outside;
- the left column gets the remaining width and ellipsizes; if it would fall
  below 84 dp, the total moves to its **own line** instead of squeezing;
- text shrinks only as a last resort (8 sp floor), and the total’s left edge is
  clamped to the row padding.

Dashboard `AcTotalRow` uses the same rule: total at its measured width on the
same line; left label ellipsizes; below the 84 dp minimum the total drops to
its own line. This also matches the 09d “5,387,279” layout.

CI matrix: `LootRowLayoutTest` renders **96 screenshots** —
widths 80/100/130 % of 300 dp, font scale 1.0 and 1.3, portrait and landscape,
values 0, 1, 999, 1,000, 999,999, 1,000,000, 5,711,279 and 999,999,999,999.
Each case asserts: view width ≥ measured paint width and line width, total
inside the row, total below the label when in two-line mode, and that the total
actually has ink in the rendered bitmap. Files land in
`app/build/reports/loot-matrix/`; `.github/workflows/android.yml` uploads the
`loot-clipping-matrix` artifact on every run (pass or fail).

## 8. Loot capture

### Analysis of the latest available 12 attacks (live4, build 2.8.0)

| Result | Count | Evidence |
|---|---:|---|
| Complete, no review | 4 | attacks.json |
| Loot not captured | 7 | `png=-`; logs: `loot did not stabilize; saving without values` |
| All values but wrong bonus | 1 | 2,697,000 read instead of 269,700; old build stored it and only added a review reason |

The old log shows the failure mechanism: the first “clean” frame frequently
returned `no damage anchor`; the detection-frame fallback read later, but the
old code required two stable reads ≥1.6 s apart and the result screen changed
or closed first. There were also repeated `screenshot failed` polls and periods
with `game not foreground` while the screen was being read. Those records had
no saved frames (`png=-`), so Re-scan cannot repair them.

The new episode policy (already from the previous round) polls 250 ms/500 ms,
accepts main loot after two equal frames, accepts bonus after three equal frames
with stable row boxes, resolves “no bonus panel” after 4 s, caps at 12 s, and
writes exactly one record. Sanitization happens before acceptance, so a
2,697,000 bonus is dropped instead of stored.

### Return Home hold

- `ResultCaptureGate` now holds an in-flight Return Home tap for at most
  **10 seconds** (`HOLD_TIMEOUT_MS = 10_000`), and releases it as soon as the
  episode finishes. The 10 s default is asserted by a unit test.
- Every gesture is now gated while an episode is active, not only taps in the
  last 15 s, so a delayed Return Home cannot dismiss the result screen during
  capture.
- *Not measured:* remaining `Closed early` count and the ≥95 %/0-wrong target
  on **20 real attacks**. That requires the device pass; the old 12 records
  above predate the episode path and are not representative of it.

## Acceptance status

| Item | Status |
|---|---|
| Original logo everywhere; no old drawing refs | Code/resource pass; device screenshots pending |
| Launcher normal/round/themed, splash, notification | Assets pass; device screenshots pending |
| 3 save/discard/auto-save flows | Code fixed; device pending |
| Strips at 80/100/130 % and long names | Code pass; device pending |
| Boxed floating list | Code pass; device pending |
| Overlay Activity launch audit | Static audit pass; list above |
| History/session boxes expand, attack sheet, banner | Code + filtering tests pass; device pending |
| Pluralization | Audited + `PluralTest` pass |
| Clipping matrix in CI | 96 screenshots generated; CI upload added |
| 20 real attacks, ≥95 %, 0 wrong | **Blocked: no device connected** |
| Screenshots vs pack PNGs | **Blocked: no device connected** |

No pass is claimed for the blocked device rows. When the phone is connected,
install the APK in place (same stable key), leave `macros.json`/`attacks.json`
untouched, and run the three save flows, the strip sizes, the boxed list, the
History page and 20 real attacks; the event log now records per-frame values and
episode milestones for the capture-rate report.
