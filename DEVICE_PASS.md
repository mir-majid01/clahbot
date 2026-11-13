# AutoClash device pass — 2.12.1 / versionCode 34 batch + data-safety Fixes 1–5

Purpose: validate the **shipping** APK on the Infinix GT 30 (X6876). Nothing here is
"already tested" — every line needs device evidence. Fixes are made only for tests
marked FAIL with the evidence attached. Code inspection and unit tests are recorded
separately and are **never** reported as a device PASS.

Prepared 2026-11-09 against the current working tree (uncommitted batch: 44 files,
+2196/−755, plus Fixes 1–5). Full unit gate is green (app 402/0/0/2, ocrlab 50/0/0/0)
but **compilation and unit tests are not feature verification**.

> This file is a local verification artifact and must **never be committed**
> (same for `DEVICE_RESULTS.md`, `.scratch/`, `device-backup/`, `shots/`, `logs/`).

---

## Read this first — the build/evidence correction

The previous checklist assumed a **debug** APK under test with `run-as` evidence.
That is wrong for this batch, for two reasons:

1. **The shipping artifact is a MINIFIED RELEASE APK.** `app/build.gradle.kts:44-45`
   sets `isMinifyEnabled = true` / `isShrinkResources = true` for `release`. The
   Auto Walls self-disable defect (#34 / T5) is **release-only**: R8 renamed the Gson
   fields, so `walls.json` was written as `{"a":{"a":true,…}}` and every reload read
   defaults. The fix is in this batch (`proguard-rules.pro:8-9`
   `-keep class com.clahbot.walls.**` / `com.clahbot.recovery.**`, plus
   `@SerializedName` on every `WallSettings`/`WallRunLog` field in `WallModels.kt`).
   **A debug build is not obfuscated, so it falsely passes T5.** T5 (and every
   release-only item) MUST be judged on the minified release APK.

2. **A release build is not debuggable, so `run-as` fails and there is no root.**
   `AppEventLog` mirrors every line to logcat (`Log.i("AutoClashEventLog", …)`)
   precisely so a release build stays diagnosable. Therefore:
   - **Primary evidence on release = `adb logcat` + screenshots + observed UI.**
   - `ResultsCaptureCoordinator` also logs under its own tag `AutoClashResults`,
     and `warn()` calls `Log.w(TAG, msg, throwable)` — so the **full crash stack
     trace is in logcat under `AutoClashResults`** even though the private events
     file stores only the message. Capture both tags.
   - **Byte-level JSON evidence** (backups, `walls.json` keys, a new `attacks.json`
     id, `recovery.json` survival) needs a **debuggable** build. Debug and release
     share the same signing key (`build.gradle.kts:40`), so a debuggable APK
     installs in place over release **without clearing data** and can `run-as cat`
     the bytes. Use it only as a **read-only forensic aid**, never to judge
     release-only behaviour except for reading bytes the release build wrote.

**Artifact under test:** ONE release APK built by **GitHub Actions** (do not build a
local substitute). Record its `versionCode`, byte size, sha256 and signer before
installing. A debuggable APK of the same key is a forensic tool, not a second
artifact under test.

---

## Gate-blocking defects from the 2026-11-08 pass — fixes applied 2026-11-09

Both defect fixes are now in the tree and unit-green (`:app:testDebugUnitTest`
**409 tests, 0 failures, 2 skipped**). Neither is device-verified: T1 needs the
`stable → saved` log lines and a new id in `attacks.json`; T6 needs one `Run now`
that prints the refusal box. The release gate stays **HOLD** until both re-pass.

- **L1 / T1 — loot capture aborts with `CalledFromWrongThreadException`.**
  FIXED in code: `ResultsCaptureCoordinator.emitEpisode()` hands the flag to
  `EpisodeEffectsBridge(Dispatchers.Main, onEpisodeActive)` (new file
  `results/EpisodeEffectsBridge.kt`), so both edges run on main while the poll loop
  stays on `Dispatchers.Default`. `TouchEffectsView.suppressed` now clears its
  collections on main (`post` when called off-thread) and only invalidates there.
  `cancel()` keeps the direct `(false)` call: the setter writes views only when the
  flag turns on, so that edge cannot throw. Regression: `EpisodeEffectsBridgeTest`
  (2 tests) proves both edges land on the main dispatcher, in order. Unit evidence
  only — the device re-test below is still required.

- **W1 — Shop-tile hit was a true positive; a separate grass FALSE POSITIVE is now
  fixed (2026-11-10). T6 still open.** The original refusing box was the **Shop**
  tile: `hit=gem@2436,508-2607,670`, and the recorded drag
  `(0.907,0.598) → (0.936,0.271)` ran straight through it — the guard did its job and
  that macro must be re-recorded clear of the bottom-right HUD.
  **But the user then recorded a fresh village macro with no gem popup and it was
  still refused.** An offline probe of their home-village frame proved the detector
  was also flagging terrain: **51 forbidden `kind=gem` boxes**, 45 of them muted
  olive grass/foliage (mean RGB ≈ 114,145,70, saturation ≈ 0.50, green-dominance
  g−max(r,b) ≈ 31) scattered across the whole play area, each expanded 100%. Any
  village gesture hit one and stopped.
  **Fix:** gem detection now uses its own stricter green mask requiring vivid
  green-dominance `g − max(r,b) ≥ 60` (`GEM_MIN_GREEN_DOMINANCE` in
  `GameScreenEngine`); the confirm-button mask, Wall Ring, captions and scrim are
  untouched. Grass (gDom ≤ 39) is excluded; real gem icons (gDom ≥ 82) stay
  forbidden. Probe re-run on the same frame: **51 → 4** boxes, all vivid-green edge
  UI (top-left profile, top-right resource HUD, far-right button), none over the
  village centre. Regression: `GameScreenEngineTest` +`village grass and foliage are
  not forbidden gem boxes` (RED before fix) and +`a vivid gem icon stays forbidden
  in the open village without any caption` (safety guard). Engine suite 18/18; full
  gate 461 tests / 0 failures / 0 errors / 2 pre-existing skips.
  The diagnostics are permanent: `ScreenBox.kind` → `gem` / `ring` /
  `caption:<word>`, and `WallLogic.firstForbiddenHit` + `describeForbidden` reporting
  state, frame size, the hit box, the hitting point and the gesture timestamp.
  `WallAutomation` logs `walls: never-press refusal — …` before it stops.
  `WallLogicTest` +5 tests lock the reported drag's geometry.
  **Installed on device 2026-11-10 12:28 (device time):** built `assembleRelease`
  (gate 461/0/0/2 first), sha256 `1823daa70d17698c1af8423c70aa71ac1ba5b08a8d9f172dcd4b4c9fbad4d31d`,
  `adb install -r` Success. Now a non-debuggable release (DEBUGGABLE flag cleared);
  ceDataInode/deDataInode unchanged (1258529/1293857) → data preserved. Pre-install
  backup + sha256 in `device-backup/pre-gemfix-install-20261110/`. App launches clean
  (MainActivity foreground, no fatal in logcat); dashboard shows preserved session,
  automation stats and Auto Walls ON.
  **Device re-test required:** run a village wall macro clear of the HUD — it must no
  longer be refused by grass; then open a real gem offer mid-run and confirm the
  guard still refuses (T7).

---

## Hard constraints (MUST remain in effect during the pass)

- **Do not uninstall the app. Do not reset or delete user data.**
- `macros.json` is **irreplaceable**. Before modifying `attacks.json`, `macros.json`,
  `sessions.json`, `walls.json`, `recovery.json` or any statistics/config file,
  **back it up and record its sha256**. Preserve existing attack macros, wall macros,
  settings, statistics and user configuration.
- **Never weaken a test/check to make it pass.** Prove a refusal test by removing the
  guard, not by relaxing the assertion.
- **Do not deliberately corrupt live user JSON to "test" Fixes 1–4 on device.** Those
  quarantine paths are unit-verified; corrupting irreplaceable files violates the
  data constraint. See "Fixes 1–4" below.
- Android taps: **check `mCurrentFocus` first**; no PIN bypass; **no taps while the
  user is using the phone**. Use `MSYS_NO_PATHCONV=1` for `/sdcard` paths (Git-Bash
  mangles them). `bc` is unavailable → use `awk`.
- No backend/cloud/account. No simulated functionality. No commit/push/tag/release/
  install without explicit authorization (prior v34 approval is **not** blanket).
- If a feature cannot be verified on a real device, **state the limitation
  explicitly** — do not infer a PASS from code or unit tests.

---

## 0. Setup

Tooling: adb at `C:/Users/HP/AppData/Local/Android/Sdk/platform-tools/adb.exe`.
Device serial `1541831584021235`, 1224×2720, Android 16. In the commands below,
`adb` means that full path; prefix Android-path commands with `MSYS_NO_PATHCONV=1`.

```bash
# 0.1 Device + focus (never tap blind)
adb devices
MSYS_NO_PATHCONV=1 adb shell dumpsys window | grep mCurrentFocus
adb shell wm size                       # confirm 1224x2720 (or current rotation)

# 0.2 What is installed right now? (debuggable flag decides the backup path)
adb shell dumpsys package com.clahbot | grep -E "versionCode|versionName|flags"
#   flags contains DEBUGGABLE -> run-as works now.
#   HAS_CODE, no DEBUGGABLE   -> release; run-as will fail (see 0.3b).

mkdir -p device-backup logs shots
```

**0.3a Back up user JSON + hashes (current build is debuggable):**

```bash
for f in macros attacks sessions walls recovery; do
  adb exec-out run-as com.clahbot cat files/$f.json > device-backup/$f.json
done
sha256sum device-backup/*.json > device-backup/HASHES.txt
```

**0.3b Back up when only a release build is installed (no root):** a debuggable APK
of the **same key** installs in place and preserves data. Install it solely to take
the backup, then install the release artifact for the pass:

```bash
adb install -r <debuggable-apk-same-key>     # data preserved; run-as now works
# run 0.3a, then:
adb install -r <CI-release-apk>              # the artifact under test
```

If no debuggable APK is available, **state the limitation**: byte-level backups are
unavailable without root; proceed with logcat + screenshots and record that the
pre-pass JSON hashes could not be captured.

**0.4 Install the release artifact under test (data preserved, same key):**

```bash
adb install -r <CI-release-apk>
adb shell dumpsys package com.clahbot | grep -E "versionCode|lastUpdateTime"
# Record artifact size + sha256 + signer in DEVICE_RESULTS.md.
```

**0.5 Evidence commands (release-safe — logcat needs no run-as):**

```bash
# Primary: both event tags. AutoClashResults:I includes the W-level crash stack.
adb logcat -d -s AutoClashEventLog:I AutoClashResults:I > logs/T<N>-logcat.txt
adb exec-out screencap -p > shots/T<N>-<what>.png

# Forensic byte pull ONLY when a debuggable build is installed (0.3b / post-pass):
adb exec-out run-as com.clahbot cat files/attacks.json   > logs/T<N>-attacks.json
adb exec-out run-as com.clahbot cat files/walls.json     > logs/T<N>-walls.json
adb exec-out run-as com.clahbot cat files/recovery.json  > logs/T<N>-recovery.json
adb exec-out run-as com.clahbot cat files/autoclash_events.log > logs/T<N>-events.log
```

**Preconditions that cannot be skipped:**

- **T3** needs Settings → "Results capture (debug)" → **"Save results screenshots"
  ON** (default OFF). Without it no frame is kept and Re-scan can only answer
  "N without a readable saved frame".
- **T9's progress bar** needs a published release with **versionCode > 34**. Installed
  34 vs latest release v34 ⇒ only "You are up to date." is reachable today.
- **T5** must be judged on the **minified release** APK (see above). A debug PASS for
  T5 is recorded as **NOT VERIFIED (release-only)**, never as PASS.

---

## T1 — Loot capture and immediate persistence  ·  KNOWN-OPEN (L1)

**Do**
1. Start the floating controller (overlay **visible** — this is the crash trigger),
   Macros tab, pick an attack macro that holds the results screen ~3–4 s, press Play
   (or a loop of ≥3).
2. Let one attack finish. Do not touch the screen while the result numbers show.
3. Watch the controller **Stats** tab as the numbers settle, then open the dashboard
   and switch Statistics between **Session** and **All time**.

**Expected (if the batch had fixed L1 — it has not):** session gold/elixir rise within
~1 s of settling; one record per attack; logcat shows
`main loot stable at …; saving now` then `record saved id=… lootCaptured=true`.

**Expected as the code stands (known-open):** with the overlay visible, capture
throws at episode start and saves nothing. Capture this evidence:

```bash
adb logcat -d -s AutoClashResults:W AutoClashEventLog:I > logs/T1-crash.txt
```
Look for `WARN Results capture failed: Only the original thread that created a view
hierarchy can touch its views. Expected: main  Calling: DefaultDispatcher-worker-…`
and the **stack trace under `AutoClashResults`** naming
`TouchEffectsView.suppressed` → `invalidate()` → `ViewRootImpl.checkThread()`, plus
`watch finally … records=0`.

**Genuine bug (confirmed open):** `CalledFromWrongThreadException`, `records=0`, no
new id in `attacks.json`, loot only appears after History/Re-scan or not at all.
**Release gate: HOLD.** Do not report T1 PASS without the stable→saved log lines and
a new persisted id.

---

## T2 — Session vs Lifetime vs History stay separate  ·  blocked by T1

**Do**
1. With the current session active, note **Session** totals + attack count.
2. Switch to **All time** and compare.
3. Open **See all history**; check each session block's header count vs its rows.
4. Leave Clash (Home) for **≥70 s** so the 60 s grace closes the session, return, run
   one more attack.

**Expected:** Session counts only the current session; All time ≥ Session and keeps
older sessions; History rows sum to the session total and header count = row count;
after the 70 s break the next attack starts a **new** session block; All time grows by
exactly that attack.

**Capture if it fails:** screenshots of Session, All time, both History blocks;
`sessions.json` + `attacks.json` (forensic pull); logcat.

**Genuine bug:** Session includes a previous session's loot; All time < Session;
header count ≠ rows; a confirmed History attack missing from its session.
**Note:** needs a saved attack to compare — if T1 saves nothing, T2 is **BLOCKED**.

---

## T3 — Re-scan corrects without double counting  ·  blocked by T1

**Precondition:** "Save results screenshots" ON (section 0).

**Do**
1. Run one attack; confirm a new `<base>.png` + `.json` + `.txt` in
   `Android/data/com.clahbot/files/results_captures`.
2. Open that attack → **Edit** ("Edit attack loot") → set Gold `500000` **and clear
   Elixir** → Save. Note All time gold (G0).
3. Tap **Re-scan** (dashboard review banner or History). Note status + new All time
   gold (G1) + attack count.

**Expected:** record leaves the review set; gold = the screenshot's real value,
elixir filled; `G1 − G0 = (real gold − 500000)` (delta only); count unchanged; one
History row; one id in `attacks.json`; status like `Re-scanned 1; updated 1`.

**Capture if it fails:** before/after All time + record screenshots; `attacks.json`
before/after; Re-scan status; logcat (`rescan failed …` / `rescan skipped …`).

**Genuine bug:** count increases; total jumps by the full corrected gold; duplicate
row; "no saved frame" while capture files exist. **BLOCKED if T1 saves nothing.**

---

## T4 — Recording: naming, undo, rename  ·  batch item 1–3 (unit-green)

**Do**
1. Macros tab → **● Record** → 3–4 distinct taps → **Finish**.
2. Review panel: name field must be **empty** with only a grey hint; no countdown, no
   progress bar.
3. **Save** with the field empty → expect refusal ("Enter a macro name").
4. Type an existing macro's name → Save → expect "A macro named … already exists".
5. **Undo icon** in the recording bar once → gesture count drops by exactly 1; use to
   0 → "Nothing to undo yet".
6. Save with a unique name; rename from the macro settings sheet and from the
   dashboard → Save.

**Expected:** no auto-save, no timer; undo removes exactly one action and refuses
once saved ("This recording is already saved"); rename applies immediately, list
updates at once, ID/actions preserved.

**Capture if it fails:** review panel before/after undo, error toast, rename sheet;
`macros.json` (forensic); logcat (`recording: undo dropped … N left`).

**Genuine bug:** saved without a name; undo removes 0 or 2; rename needs a re-open;
duplicate names accepted.

---

## T5 — Auto Walls persistence  ·  RELEASE-ONLY (#34 / batch item 4)

**Must be judged on the minified release APK. A debug PASS = NOT VERIFIED.**

**Do**
1. Walls tab → switch **ON**, set **Cost per wall** (e.g. 1000000), **Keep in
   reserve** 0, pick a wall macro.
2. Switch Macros → Stats → back to Walls. Then Home → reopen the controller.
3. **Force-stop** AutoClash (Settings → Apps → AutoClash → Force stop) and reopen.

**Expected:** after every step the switch is still ON, cost + macro unchanged, no
`· not saved: …` on the status line. Logcat after each reload shows:
`walls: loaded enabled=true macro=<id> cost=1000000 reserve=0 tested=<id>` —
**not** `walls: file has no settings object; using defaults` (the broken-release
line) and **not** `walls: WRITE FAILED …`.

**Byte-level proof (forensic, optional but decisive):** after the release pass,
install a debuggable APK of the same key (data preserved), change **no** wall
setting, and pull the bytes the release wrote:
```bash
adb exec-out run-as com.clahbot cat files/walls.json > logs/T5-walls-release-wrote.json
```
It must contain real keys `"settings":{"enabled":true,…,"costPerWall":1000000,…}`,
**not** `{"a":{"a":true,…}}`. This proves the R8 fix on the shipping build.

**Capture if it fails:** screenshots after each switch + after force-stop; logcat
(`walls: loaded …`, `walls: file has no settings object …`, `walls: WRITE FAILED …`);
the forensic `walls.json`.

**Genuine bug:** switch flips OFF after any tab switch / restart (the 2.12.1
release defect); cost resets; status reports a save failure; `walls.json` written
with obfuscated keys. **Release gate: HOLD if the release build self-disables.**

---

## T6 — Wall Test (dry run) + Stop  ·  BLOCKED: re-record the wall macro first

The 2026-11-09 run showed **Test macro** and **Run now** both stopping on the Shop
tile, so this test cannot produce a clean dry run until the wall macro is re-recorded
with its 3372 ms drag inside the village and away from the bottom-right HUD.

**Do**
1. With enough loot, tap **Test macro**. Watch the strip + screen.
2. Tap **Test macro** twice quickly.
3. Tap **Run now** with a ~8–10 s macro, then press the strip's **■** mid-run.

**Expected (if the box were identified/cleared):** Test taps nothing, spends no loot,
reports `Dry run: would run "…" paying from gold…`; "Last upgrades" unchanged; the
blue strip is visible from the first moment; a second Test answers "A wall run is
already going"; **■** stops within one gesture and the next Test starts normally.

**Expected as the code stands (known-open):** `Run now` is refused before any gesture
with `Wall macro gesture at <ts>ms hits a never-press box` (status `stopped`), and the
box is **not** dumped, so it cannot yet be identified. Capture:
```bash
adb logcat -d -s AutoClashEventLog:I > logs/T6-refusal.txt
adb exec-out screencap -p > shots/T6-home-village.png
```
Record the refusal detail, the macro id, `testedWallMacroId`, and the home-village
frame. **Do not** change detection thresholds to force a PASS.

**Genuine bug:** Test spends loot; stop does not stop; strip never appears; after a
stop the next Test still says "already going" with no strip. **Open:** the refusal
box is unidentified — diagnosis instrumentation must be added before this can pass.
**Release gate: HOLD.**

---

## T7 — Gem popup safety  ·  batch item 10 (device pending)

**Do**
1. Open the Clash Shop (or a gem offer), leave the dialog on screen. From the
   controller: Walls → **Run now**.
2. Run an attack loop and, mid-loop, open a gem offer so a dialog is up between
   repetitions.

**Expected:** the wall run is refused **before any gesture** (toast/status naming the
never-press box), nothing purchased, gems unchanged; in the loop, within ~6 s the
amber **"Closing a popup…"** strip appears and the dialog is closed with the red ✕
only, then the loop resumes; if it cannot be cleared the loop stops and logs it (no
blind tapping). No automation ever taps Buy / Gem / "Upgrade with gems" / Wall Ring.

**Capture if it fails:** dialog + strip screenshot; logcat; dashboard Automation
card recovery counters (Recovered / Stopped).

**Genuine bug:** any purchase dialog confirmed; gems spent; a tap lands on Buy or the
Wall Ring; the loop keeps tapping into the dialog. **Safety-critical — HOLD on any
purchase.**

---

## T8 — Auto Walls card collapse state  ·  batch item 19 (unit-green)

**Do**
1. Dashboard → **Auto Walls** header → tap **▾**. Collapse it.
2. Scroll away and back; switch apps and return; force-stop and reopen.
3. While collapsed, check the header shows the **ON/OFF** pill.

**Expected:** collapsed shows only title + ON/OFF + switch + ▴; expanded shows full
controls; state survives scrolling, app switch and restart (persisted via
`SettingsStore.wallsCardExpanded`, default collapsed).

**Capture if it fails:** collapsed/expanded + after-restart screenshots.

**Genuine bug:** state resets on recomposition or restart; ON/OFF indicator
disappears when collapsed.

---

## T9 — Updater progress  ·  batch item 18 (device pending)

**Do now:** Settings → **Updates** → **Check GitHub for updates**. Expected today:
`You are up to date.` (installed 34, latest release v34).

**Do after a release with versionCode > 34 exists:**
1. Check again → `Update available: …` + APK size line.
2. Tap install/download → progress bar with `downloaded / total · % · speed/s`, then
   `Verifying download size and SHA-256…`, then the Android installer.
3. Start again and tap **Cancel download** mid-transfer.

**Expected:** every number is real — % moves only when bytes move; speed plausible;
unknown total → indeterminate bar with bytes but no %; cancel leaves no partial APK
in `cache/updates`; an unverified file never reaches the installer.

**Capture if it fails:** screenshots at ~25 %, ~75 %, verifying; screen recording if
the bar stalls; logcat for `GitHubUpdater`.

**Genuine bug:** % advances with no byte growth; fabricated speed; cancel does not
stop the transfer; an unverified file reaches the installer. **BLOCKED until a
versionCode > 34 release is published** (only the up-to-date path is testable now).

---

## T10 — Recovery survives floating-controller-only playback  ·  NEW (Fix 5)

This is the exact clobber trigger Fix 5 addresses: the service watchdog writes
`recovery.json` via `RecoveryRepositoryHolder.get()` **without** any `refresh()`
(only `MainActivity` refreshes — `MainActivity.kt:415,1030,1051`). Before the fix the
singleton started empty, so a write-before-read destroyed taught rules + stats.

**Do**
1. Back up `recovery.json` + hash (section 0). Note its `stats.recovered`,
   `stats.stopped`, and `rules` (taught recoveries). If none exist, teach one recovery
   rule first from the dashboard so there is something to lose.
2. **Force-stop the app. Do NOT open the dashboard/MainActivity.** Start the floating
   controller directly and play a macro that triggers the recovery watchdog (e.g. a
   popup recovery), so `noteRecovered()`/`noteStopped()`/`addUnknownScreen()` fire
   from the service.
3. Only now open the dashboard → Automation card.

**Expected:** the dashboard Recovered/Stopped counters reflect the persisted baseline
**plus** the new events (not reset to 0/1); taught rules still apply during playback;
`recovery.json` (forensic pull) still contains the pre-existing `rules` and the
correct cumulative `stats` — the service write did **not** clobber them.

**Capture if it fails:** before/after `recovery.json` + hashes; dashboard Automation
screenshots; logcat (`playback aborted by recovery: …`). Note: recovery writes have
no dedicated `AppEventLog` line, so byte + UI evidence is primary.

**Genuine bug:** stats reset to a small number after floating-only playback; taught
rules vanish from `recovery.json`; a taught recovery no longer fires. **Data-loss —
HOLD.**

---

## T11 — Dashboard ↔ floating selector sync  ·  batch item 20

**Wall selector (confirmed consistent in code — verify on device):**
1. Pick a wall macro on the dashboard; check the floating Walls tab shows the same.
2. Pick a different wall macro on the floating controller; check the dashboard
   follows. Delete the selected wall macro; both surfaces must clear the selection
   (`gone · select it again`) without turning Auto Walls off or forgetting the cost.

**Attack selector (POTENTIAL DESYNC — observe, do not assume):**
3. With the floating controller **already running** and a local attack macro
   selected, change the selected attack macro on the **dashboard**. Observe whether
   the running controller adopts it.

**Expected:** wall selection is identical on both surfaces and survives deletion
(shared `wallsRepository` singleton + reactive reconcile). For the attack selector,
**record the observed behaviour**: the service does not collect
`BotState.selectedMacroId` and only adopts it in `onMacrosChanged` when its local
`selectedId <= 0` (`FloatingMenuService.kt:654`), so a dashboard change may NOT be
adopted by an already-running controller. Whether that is a defect or intended
"controller-local selection wins" UX is a **device/UX judgement** — capture it, do
not silently "fix" it.

**Capture:** screenshots of both surfaces before/after each change; `macros.json` /
`walls.json` (forensic); the observed attack-selector adoption behaviour.

**Genuine bug (wall):** selections diverge; deletion turns Auto Walls off or resets
cost; a dangling link blocks editing. **Attack selector:** record as a finding for
the ten-item report, not an automatic FAIL.

---

## Fixes 1–4 — repository read-failure quarantine  ·  UNIT-VERIFIED, not a device test

Fixes 1–4 (Macro / Attack / Session / Walls repositories) quarantine an unreadable or
corrupt JSON original (`.corrupt-<ts>`) before falling back to empty/defaults, so a
later read-modify-write cannot clobber irreplaceable data. Each was TDD-verified
(RED→GREEN) in the unit gate.

**Do NOT test these on device by corrupting live `macros.json` / `attacks.json` /
`sessions.json` / `walls.json`.** That would risk irreplaceable user data and violate
the hard constraints. Record them as **unit-verified, device-corruption deliberately
not performed**. The only device-facing data-safety test is **T10** (Fix 5), which is
non-destructive. If a real corruption occurs naturally during the pass, capture the
`.corrupt-<ts>` file as evidence and restore from the section-0 backup.

---

## Results to send back (feeds the ten-item final report, item 23 / #43)

| # | Test | Batch item | Result | Evidence file(s) |
|---|------|-----------|--------|------------------|
| T1 | Loot capture + persistence | 11 (L1 known-open) | PASS / FAIL / BLOCKED | |
| T2 | Session vs Lifetime vs History | 12/13/16 | | |
| T3 | Re-scan delta | 14/15 | | |
| T4 | Naming / undo / rename | 1/2/3 | | |
| T5 | Walls persistence (**release-only**) | 4 / #34 | | |
| T6 | Wall Test + Stop (W1 known-open) | 6/7/10 | | |
| T7 | Gem popup safety | 10 | | |
| T8 | Card collapse | 19 | | |
| T9 | Updater progress | 18 | | |
| T10 | Recovery survives floating-only playback | 21 (Fix 5) | | |
| T11 | Dashboard ↔ floating selector sync | 20 | | |
| — | Fixes 1–4 quarantine | 21 | UNIT-VERIFIED (no device corruption) | unit gate |

**Release gate decision:** RELEASE only if every safety/data test passes on the
**minified release** APK. **HOLD** if T1 (crash), T5 (release self-disable), T6
(refusal), T7 (any purchase) or T10 (data loss) fail. T2/T3 are BLOCKED while T1
saves nothing; T9 is BLOCKED until a versionCode > 34 release exists.

**Anything not listed as PASS is treated as unverified, not as working.** After the
pass, record results in `DEVICE_RESULTS.md` (never committed) and produce the
ten-item final report. No version bump, commit, tag or publish until the gate clears.