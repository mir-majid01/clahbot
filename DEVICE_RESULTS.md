# Device results — 2.12.1 / versionCode 34 batch

Recorded 2026-11-08. Device: Infinix X6876, Android 16, adb serial `1541831584021235`.
Checklist: `DEVICE_PASS.md`. PASS requires device evidence; code inspection and
unit tests are recorded separately and are never reported as a device PASS.

Artifact under test: `app/build/outputs/apk/debug/app-debug.apk`
61,244,191 bytes, sha256 `e7c19e933dab59bb5a6db4d500694485ad524d61d2b782d16cc84f8c2bcdad35`,
V2 signer `67198675…ad9d47` — same key as 2.12.1, installed in place over the
release build (`lastUpdateTime` 2026-11-08 14:46) without clearing data.

## Results

| # | Test | Result | Evidence |
|---|------|--------|----------|
| S0 | Install + artifact integrity | **PASS** | installed `base.apk` sha256 identical to the built APK; `versionCode=34`; app launches |
| S1 | User data preserved across the update | **PASS** | `macros.json` 68,671 B (107 ids), `attacks.json` 1,280 B (2), `sessions.json` 937 B (3), `autoclash_events.log` 181,731 B after install |
| L1 | Loot capture during real gameplay | **FAIL — confirmed** | results screen detected, capture throws `CalledFromWrongThreadException`, `records=0` |
| T1 | Loot capture + immediate persistence | **FAIL — confirmed** | `attacks.json` untouched since 11-05 19:06; 13 watches ended `records=0`; two aborts at capture |
| T2 | Session vs Lifetime vs History | **BLOCKED** | needs a league-table comparison after a saved attack; nothing saved to compare |
| T3 | Re-scan delta without double counting | **BLOCKED** | needs a saved results frame and a manual edit; "Save results screenshots" is OFF by default |
| T4 | Recording naming / undo / rename | **NOT TESTED** | `macros.json` grew 107 → 114 (recording works), naming/undo/rename unobserved |
| W1 | Wall macro playback refusal | **PASS — true positive, box identified** | refusal now names the box: `hit=gem@2436,508-2607,670`, which is the **Shop** tile; the recorded drag passes through it |
| T5 | Auto Walls persistence | **FAIL (release build only)** | shipped 2.12.1 wrote minified `walls.json`; debug build persists correctly (below) |
| T6 | Wall Test (dry run) + Stop | **BLOCKED — by the macro, not the guard** | `walls: test stopped` with the same box; no gesture replayed, so the dry-run path itself is still unexercised |
| T7 | Gem popup safety | **BLOCKED** | no shop/gem dialog was raised during a run |
| T8 | Auto Walls card collapse state | **BLOCKED** | not exercised |
| T9 | Updater progress bar | **BLOCKED** | installed 34 vs latest release v34; only `You are up to date.` is reachable |

Release gate: **HOLD**. L1/T1 is still unverified and T5 still fails on the build that
ships. W1 is now explained (true positive, the wall macro must be re-recorded) and
T6 stays BLOCKED on that re-record, not on the guard.

---

# L1 / T1 — loot capture aborts at the moment the results screen is found

This is the prioritized defect. The pipeline is **not** falling back to old logic;
the new path runs, detects the screen, and then throws before it can read or save.

## Confirmed facts (device evidence)

```
11-08 15:06:26.270 poll #62 t=75223ms shot=56ms detect=283ms results=true \
                  hash=007c2c7a3e5c36bd action=CAPTURE records=0 method=window
11-08 15:06:26.283 WARN Results capture failed: Only the original thread that \
                  created a view hierarchy can touch its views. \
                  Expected: main  Calling: DefaultDispatcher-worker-3
11-08 15:06:26.292 watch finally id=1794130510705 polls=62 records=0 ...
```

The same failure is in the 11-05 log at 19:06:00.757. Supporting state:

- `files/attacks.json` on the device is still the 2-record file from 11-05 19:06 —
  no attack has been persisted since the install.
- 13 `watch finally … records=0` lines in `autoclash_events.log`.
- The single record that exists (11-05 19:06:08) was written after the same
  failure and carries `review=Loot not settled; Bonus missing; Closed early` —
  i.e. an incomplete fallback record, which is why the behaviour looks unchanged.
- `results=true action=CAPTURE` proves detection and the frame hash work; the
  window screenshot path also logs `code=3` and falls back to display capture.

## Exact call path (source)

| Step | File | Function / line |
|---|---|---|
| poll → detect | `app/src/main/java/com/clahbot/results/ResultsCaptureCoordinator.kt` | `watch()` — poll loop, `action=CAPTURE` |
| episode start | `ResultsCaptureCoordinator.kt:287` | `onEpisodeActive(true)` called on `Dispatchers.Default` |
| callback | `app/src/main/java/com/clahbot/service/FloatingMenuService.kt:331` | `onEpisodeActive = { active -> effectsView?.suppressed = active }` |
| throwing write | `app/src/main/java/com/clahbot/overlay/TouchEffectsView.kt:61-69` | `var suppressed` setter calls `invalidate()` |
| catch site | `ResultsCaptureCoordinator.kt:332-336` | `warn("Results capture failed", …)` → `records=0` |

`invalidate()` on a View from a worker thread is exactly the exception text
(`ViewRootImpl.checkThread()`). The only View mutation inside the watch coroutine
is this one; the callback also fires `false` from the `finally` on the same worker
thread (line 320/158), which does not throw only because the setter skips
`invalidate()` when the value is `false`.

### Confirmed vs suspected

- **Confirmed:** capture aborts with `CalledFromWrongThreadException` at episode
  start, on a `Dispatchers.Default` worker, and no record is saved.
- **Identified call path, not stack-proven:** the thrown line is `invalidate()` in
  `TouchEffectsView.suppressed`, because it is the sole View write on that path.
  `AppEventLog.warn` stores only `throwable.message`, so the stack is not in the
  log. Fixing the thread hop is correct regardless of which View call throws.
- **Explicitly not claimed:** OCR accuracy, parsing, stability, and aggregation are
  untested until capture survives long enough to run them.

## Fix applied 2026-11-09 — unit-verified, device re-test pending

1. `ResultsCaptureCoordinator`: new `emitEpisode(active)` routes the flag through
   `EpisodeEffectsBridge(Dispatchers.Main, onEpisodeActive)`, so `onEpisodeActive(true)`
   and the cooldown `(false)` both run on the main thread while the poll loop stays on
   `Dispatchers.Default`. `cancel()` keeps a direct `onEpisodeActive(false)` — the
   effect setter only writes views when the flag turns **on**, so that edge is safe
   off-main and launching a Main coroutine from teardown would add a race.
2. `TouchEffectsView.suppressed`: main-thread path clears the effect collections and
   `invalidate()`s; an off-main caller now `post`s the same work, so the lists are never
   mutated while `onDraw`/Choreographer reads them.
3. Stack traces: no change needed — `ResultsCaptureCoordinator.warn()` already does
   `Log.w(TAG, message, throwable)` under `AutoClashResults`, which is why the device log
   carries the full stack. The events file stays message-only by design.
4. Regression test: `EpisodeEffectsBridgeTest` (2 tests) drives the bridge from
   `Dispatchers.Default` against a single-thread executor standing in for Main and
   asserts both edges arrive on that thread, in order.

Preserved behaviour: detection thresholds, OCR, stability, persistence and
aggregation are untouched. A failing overlay callback can no longer abort capture.

Gate: `:app:testDebugUnitTest` **BUILD SUCCESSFUL**, **409 tests, 0 failures, 0 errors,
2 skipped** (was 402 + 2 new bridge + 5 new wall-geometry tests).

## Device test needed to call it fixed

With the floating controller visible, run one attack that ends on the results
screen and hold it ~3 s:

```
adb exec-out run-as com.clahbot cat files/attacks.json     # mtime must change
adb exec-out run-as com.clahbot cat files/autoclash_events.log | tail -40
```

PASS requires all of: `main loot stable at …; saving now`, `record saved id=… lootCaptured=true`,
a new id in `attacks.json`, and Session + All time rising within about a second.
Without those lines the issue stays open regardless of unit tests. A screenshot of
the results screen at the moment of capture is the other useful artifact.

---

# W1 / T6 — wall playback refused by a never-press box

## Confirmed facts

`walls.json` today: Auto Walls ON, cost 1,500,000, macro **Neww**
(id 1794130405815), `testedWallMacroId` −1. Three `Run now` attempts, all stopped:

```
"detail":"Wall macro gesture at 3372ms hits a never-press box","status":"stopped"   ×3
11-08 15:04:03 / 15:04:18 / 15:04:31  walls: manual stopped
```

The offending gesture is a right-edge upward drag: `(0.907, 0.598) → (0.936, 0.271)`
normalized, `timestampMs=3372`. Nothing was purchased and no wall was upgraded.

A 2720×1224 home-village frame was captured for the record:
`device-backup/live/screen-now.png` (downscaled: `screen-now-small.jpg`).

**Offline replication on that frame produced no forbidden boxes at all** — see
below. The box that actually refused the run is therefore still unidentified.

## Where the boxes come from (source)

| Element | File | Detail |
|---|---|---|
| guard | `app/src/main/java/com/clahbot/walls/WallLogic.kt:78-100` | `unsafeTaps` / `unsafeGesture`, `crossesBox` |
| call site | `app/src/main/java/com/clahbot/walls/WallAutomation.kt:216` | `WallLogic.unsafeTaps(macro, second.observation)` |
| observation | `WallAutomation.kt:203` | `agent.observe()` after `waitForHome()` → **home village** |
| box sources | `app/src/main/java/com/clahbot/game/GameScreenEngine.kt:272-348` | `gemBoxes` (green blobs, 0.8–5.5 % width), `ringBoxes` (blue blobs, 6–22 % width, top ≥ 45 % height), `NEVER_CAPTIONS` text lines |
| expansion | `app/src/main/java/com/clahbot/game/GameState.kt:61-66` | `ScreenBox.expanded` — gems by their own width/height, rings by w/6, h/6 |
| mapping | `GameScreenEngine.kt:235-240` | `toFrameBox` scales the 240×135 blob grid to frame pixels |
| gesture → pixels | `app/src/main/java/com/clahbot/execution/MacroTimeline.kt:195-230` | `playbackPaths` denormalizes against `frameWidth/frameHeight` |

The guard runs against the **home village** observation, where the same green/blue
blob heuristics that are meant to find gem prices and the Wall Ring also match
village scenery (gem mine, trees, water, roofs). The refusal is geometrically
real for the app's own frame: the computed path does intersect some box. What is
not yet established is whether that box is a genuine purchase control, a false
positive, or a caption box from OCR.

### Offline replication: no box found (negative result)

The app's blob algorithm was reimplemented exactly (240×135 downscale, 4-connected
flood fill, same thresholds, same expansion) and run on the captured frame:

```
frame=2720x1224  gemBlobs=2  ringBlobs=0  forbiddenBoxes=0  hits=0
gesture px: (2468,732) -> (2547,332)
```

So this frame would **not** refuse the gesture. That rules out nothing by itself —
the run-time frame may have differed (troops, effects, notifications, panel
state) or the box may come from a NEVER_CAPTIONS text line, which the offline
script did not model because it needs OCR. Two consequences:

1. The "village scenery creates a phantom box" theory is **not confirmed** and is
   now the weaker of the hypotheses.
2. The only reliable answer is the app's own box dump at the moment of refusal,
   which is why the diagnostic below is the next step, not a detection change.

## Suspected causes (in order, none confirmed yet)

1. **Phantom box from village scenery** — green/blue blob thresholds are size- and
   colour-only, not tied to a dialog or to the HUD; `detectElements` runs on every
   state by design (`GameScreenEngine.kt:59-62`), so a gem mine or blue roof under
   the right-edge drag becomes a never-press box.
2. **Orientation mismatch** — `Neww` is stored `orientation=landscape`; the log
   shows both 2720×1224 and 1224×2720 frames. If the observation frame was
   portrait while the macro is landscape, `playbackPaths` maps the drag into a
   rotated frame and can land on a HUD control that the real gesture never
   touches. `playbackPaths` itself scales correctly per axis; it does not rotate.
3. **Correct refusal** — the drag genuinely crosses the shop rail / gem control,
   and the macro must be re-recorded.

## Diagnosis applied 2026-11-09 — the box is still unidentified

Instrumentation only; the refusal decision is byte-for-byte the same:

- `ScreenBox` carries `kind` (`gem` / `ring` / `caption:<word>`), preserved by `expanded()`.
- `GameScreenEngine.detectElements` tags every forbidden box with its origin.
- `WallLogic.firstForbiddenHit(macro, observation)` returns the first box the gesture
  actually touches (or crosses) plus the point and gesture timestamp;
  `describeForbidden(...)` renders `state`, `frame`, hit and up to 8 boxes.
- `WallAutomation` writes one line at refusal:
  `walls: never-press refusal — state=… frame=… hit=gem@l,t-r,b point=x,y gesture=3372ms boxes=[…]`.
- `WallLogicTest` +5 geometry tests: the reported `(0.907,0.598)→(0.936,0.271)` drag is
  still refused when a control sits on it, is allowed when the nearest box is off-path,
  is refused when it starts inside a box, and the dump names the box, frame and origin.

**Still not applied — deliberately:** the narrow detection change. Our offline
replication found no box on the saved frame, so there is nothing yet to narrow; tuning
thresholds now would be guessing. One `Run now` on the phone turns the next line in
`autoclash_events.log` into the answer.

Narrow fix once the box is identified:

- keep caption-derived boxes (`buy`, `gems`, `wallring`, …) exactly as they are;
- restrict gem/ring blob boxes to the regions where those controls can exist
  (HUD band and detected dialog/panel), instead of the whole village;
- never widen the guard's meaning: a real gem/Wall Ring purchase control must
  stay blocked, including the existing swipe/long-press coverage.

Regression tests to add in `app/src/test/java/com/clahbot/walls/WallLogicTest.kt`
with the real numbers: the `(0.907,0.598) → (0.936,0.271)` drag against the box
that actually fires, a genuine gem button on that path that must still be refused,
and a village-scenery box that must no longer refuse a legitimate wall scroll.

## Safe re-record, if the box turns out to be real

Scroll the wall list with a drag that starts and ends inside the panel's central
column (keep x ≤ 0.85), away from the right-hand shop/gem rail, and avoid
beginning the drag on a button. Record with the panel already open, then `Run now`
once (that also sets `testedWallMacroId`, which is still −1).

---

## Device result, 2026-11-09 — the box is identified: it is the Shop button

Built with the `ScreenBox.kind` + `firstForbiddenHit` diagnostics, installed in
place, and run against the live home village (`state=HOME`, `2720×1224`):

```
11-09 21:23:01.729 walls: never-press refusal — state=HOME frame=2720x1224 forbidden=8
  hit=gem@2436,508-2607,670 point=2473,663 gesture=3372ms
  boxes=[gem:80,98-146,155;gem:2176,236-2380,398;gem:2176,325-2380,490;gem:284,381-350,462;
         gem:2436,508-2607,670;gem:2154,943-2220,1024;gem:0,1042-272,1224;gem:2324,1151-2390,1205]
11-09 21:23:01.736 walls: manual stopped
11-09 21:32:03.262 walls: never-press refusal — … same hit, same boxes …
11-09 21:32:03.263 walls: test stopped
```

`device-backup/live/t6-hitbox.jpg` crops the refusing box out of the frame captured
just before the run: it is the **Shop** tile (gold pile + gem) in the bottom-right
of the village HUD. The recorded drag `(0.907,0.598) → (0.936,0.271)` enters at
`(2467,732)` and leaves at `(2545,332)`, i.e. it runs straight through that tile, so
the intersection is real — **not** a blob false positive.

Conclusion: the never-press protection behaves exactly as specified and must not be
weakened. The defect is the recorded wall macro **Neww** (`wallMacroId
1794130405815`): its 3372 ms gesture drags across the Shop button, which is a
purchase control and can never be safe. The macro has to be re-recorded with the
drag inside the village, clear of the bottom-right HUD. No code change is justified
by this evidence, and none was made.

Verdicts: **W1 PASS** — refusal is a true positive and the box is now named.
**T6 BLOCKED** — `Test macro` was pressed and stopped by the same guard, so the dry
run itself still has no clean evidence. Nothing was purchased: `testedWallMacroId`
is still `-1`, `walls.json` now holds four stopped entries, and no wall was upgraded.

---

# T5 — wall settings in a minified build (unchanged finding)

Shipped 2.12.1 wrote `{"a":{"a":true,"b":-1,"c":"BOTH",…}}`; `decode` looks for
`"settings"`, so Auto Walls reloaded as OFF. On the debug APK the same file now
reads real keys with `"enabled":true`, `"costPerWall":1500000` and the three
stopped history entries, which confirms the repository works when R8 is off and
confirms nothing about the shipped build.

Fix applied but **unverified**: `@SerializedName` on all `WallSettings`/
`WallRunLog` fields plus `-keep class com.clahbot.walls.**` and
`com.clahbot.recovery.**`. The edit compiles; `testDebugUnitTest` aborts on a
stale locked `output.bin` (environmental, directory now cleared) and
`assembleRelease` never ran. The `mapping.txt` in the build tree is from 10-31 and
proves nothing. T5 stays FAIL until a minified build writes `"settings"` and reads
it back.

---

# Environment state

- Accessibility restored and verified bound:
  `Enabled services:{{com.clahbot/com.clahbot.service.ClahAccessibilityService}}`,
  `accessibility_enabled=1`.
- Debug APK installed, `run-as` works for evidence pulls.
- Snapshots: `device-backup/after/` (post-install), `device-backup/live/`
  (during use, including `screen-now.png`).
- The screenshot referenced in the report did not arrive with the message; the
  captured home-village frame is the substitute.

# Next actions, in order

1. Approve or reject the three loot fixes above; they are the gate for T1/T2/T3.
2. Re-run one real attack with the overlay visible and capture the log + JSON
   (commands above). No PASS without those lines.
3. ~~Diagnose the never-press box~~ — done 2026-11-09: it is the Shop tile, so
   re-record the wall macro instead of touching the detection.
4. Re-run T5 against a minified build once the build environment is clean.
5. No version bump, commit or publish until that is done.

## 2026-11-09 attempt with the fixed build

- Rebuilt and installed in place over the existing debug build (`adb install -r`,
  same release key): `versionCode 34 / 2.12.1`, `lastUpdateTime 2026-11-09 18:06:38`.
  App launched clean — no `FATAL`/`AndroidRuntime` lines in logcat.
- Data preserved: `walls.json` still holds `wallMacroId 1794130405815`,
  `testedWallMacroId -1` and the three "never-press box" history entries;
  `attacks.json` (2 records) unchanged; `macros.json` 72 KB untouched.
- **T1 not run:** needs a real battle reaching the results screen. Nothing was played,
  so loot capture is still unverified on the device.
- **T6 not run on the first attempt:** the floating controller did not appear over
  the game and the device then dropped off USB mid-session (`adb devices` empty,
  including after `adb kill-server`).
- **T6 run after the device returned:** recovered by using the controller bubble from
  the app (`Start floating controller` → bubble → Walls tab) instead of the in-app
  button. `Run now` and `Test macro` both refused on the same box — see
  "the box is identified: it is the Shop button" above. Resources were never the
  blocker (gold 1,738,048 covers the 1,500,000 cost under `GOLD_FIRST`), and
  `testedWallMacroId` stays `-1` because the run never reached a gesture.
- Nothing was purchased or upgraded by this attempt.
