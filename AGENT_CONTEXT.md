# Agent Context — AutoClash Feature 1

Last updated: 2026-10-27

Scope: Feature 1 results-screen loot OCR and its integration into AutoClash.
Ranked is untouched. Product Features 2-5 have not been started.

## Current status

- The shared OCR core lives in the `:ocr` library; both the OCR lab and the
  AutoClash app consume it.
- App 2.4.0 (versionCode 9) was installed on the phone with data preserved.
  Installing it did not change `macros.json` or `attacks.json` (SHA-256 checked
  before and after).
- First real attack (2.4.0): the attack finished and the village loot increased
  by about 1.61M gold, 1.64M elixir and 14.3k dark elixir, but **no screenshot
  and no attack record were created**. The results screen was open only while
  the macro was still playing and the macro tapped Return Home at 80.2 s; the
  watcher in 2.4.0 started at playback end (about 9 s later) and missed it.
- 2.4.1 (versionCode 10) was installed and a second real attack was played. The
  watcher did start at playback start (baseline at 00:21:54, playback end at
  00:23:23), and the attack again completed (loot rose by about 1.39M gold,
  1.46M elixir and 8.8k dark), but there was still no capture. Detection ran on
  a half-scale frame where the small "You got" anchor is only about 10 px tall;
  that is the leading suspect for the missed detection. There is no per-poll
  log in 2.4.1 to prove where it failed.
- 2.4.2 (versionCode 11) changes detection to the full-resolution frame, drops
  the 1 s retry delay between polls, logs every poll's screenshot/detection
  time and the parser's anchor-miss reason, and shortens the clean-capture
  delay to 600 ms. Commit `77ea41e` passed CI (`40383387019`).
- 2.4.2 also adds a race gate, a persistent rolling event log, a manual-watch
  mode for hand-played attacks, and renames the Stats “Rec” label to
  “Gesture recorder”. Commit `d37cf3e` passed CI (`40454019394`).
- Build identification: the phone had the earlier 2.4.2 build `77ea41e`
  (`edbe26e…`, updated 00:45 local) and no captures. The reliability build
  `1c16d90…` (commit `d37cf3e`, CI `40454019394`) was installed on approval at
  10:52 local. Its installed APK hash matches the CI artifact; `macros.json`
  and `attacks.json` hashes were unchanged by the install.
- **Three real result screens were captured by 2.4.2 and read correctly.**
  Two were `New` macro playbacks and one was a hand-played `Manual watch`.
  Record 1: gold 2,749,011 / elixir 2,483,872 / dark 38,782. Record 2:
  1,412,574 / 1,533,041 / 3,233. Manual record: 802,886 / 1,069,310 / 17,348.
  All three are confirmed, `lootSource=ocr`, verified, with PNG + JSON + TXT
  sidecars. Stats after the manual capture: **Attacks: 7**, totals 4,964,471 /
  5,086,223 / 59,363, exactly the sum of the three.
- The 14 old unconfirmed records are untouched; `attacks.json` is now 20
  records (6 confirmed / 14 unconfirmed). `macros.json` is byte-identical to
  before the test.
- 2.4.3 (versionCode 12) adds the per-macro **wait between repeats** (0–600 s,
  legacy default 0.8 s), the controller/bubble countdown, foreground pause and
  stop-cancel behavior. The code compiles and 115 app unit tests pass; CI has
  not run and the build is **not installed**.
- The 30+ real-capture set has not been collected or scored.
- CI query note: GitHub’s `head_sha` filter needs the full 40-character SHA;
  a 7-character prefix returns an empty list even when the run exists. There
  was no workflow trigger problem.

## Real-capture verification (2026-10-27)

This closes the real-capture gap for macro playback. Evidence was pulled from
the phone after the test.

- Installed build: 2.4.2 / versionCode 11, installed APK SHA-256
  `1c16d90f4d7baedc931329f3cb9ff6bf166202331405c7d10731484b2ecd7bb4`,
  identical to the CI artifact for commit `d37cf3e`. **A**
- Data safety: `macros.json` SHA-256 `4a01452e…` before and after; the 14 old
  unconfirmed records and the 4 old confirmed records are unchanged.
  `attacks.json` now has 20 records: 6 confirmed / 14 unconfirmed. **A**
- Saved files under
  `Android/data/com.clahbot/files/results_captures/`:
  - `1793079022622_victory_G2749011_E2483872_D38782.{png,json,txt}`
  - `1793079380112_victory_G1412574_E1533041_D3233.{png,json,txt}`
  Both PNGs are clean full-resolution result screens with no overlay. **A**
- Record 1 (10:58 local): gold 2,749,011; elixir 2,483,872; dark 38,782;
  balances 13,621,144 / 12,128,813 / 272,027. The balances match the
  top-right counters in the PNG. **A**
- Record 2 (11:04 local): gold 1,412,574; elixir 1,533,041; dark 3,233;
  balances 15,584,025 / 14,201,976 / 280,565; matches its PNG. **A**
- Stats arithmetic: 2,749,011 + 1,412,574 = 4,161,585 gold;
  2,483,872 + 1,533,041 = 4,016,913 elixir; 38,782 + 3,233 = 42,015 dark.
  The Stats tab showed exactly these totals with Attacks: 6 and the two rows
  `10:58` and `11:04`. **A**
- Consensus evidence from the first sidecar, gold row:
  `mlkit_full` read `2.749 o11` (garbage), `mlkit_crop` read 2749011,
  `tesseract_crop` read 27495 (wrong), `rapidocr_crop` read 2749011,
  `template_crop` read 2749011; consensus chose 2749011 from three readers.
  This is a real, non-training screen, so the template vote is legitimate
  here. **A**
- Bonus fields: both PNGs show an empty grey 100% bonus panel. The app wrote
  `Bonus gold/elixir/dark: loot not captured` in the TXT and null in the JSON;
  it did not substitute 0. `needsReview=true` and confidence 0.90 reflect the
  missing bonus block; the three main loot values are present and verified. **A**
- Race gate timing, first capture:
  detection 11:00:19.939, hold requested; read 11:00:22.575; saved and record
  11:00:24.441-457; hold released 11:00:24.483; the `Return Home` gate tap
  (80.188 s) logged at 11:00:25.940, after the record was saved. The hold was
  about 4.5 s. The watcher also logged `gate tap ... screenshot failed` at
  74.357 s and 75.066 s while the battle was still running, then recovered. **C**
- Poll cadence with full-resolution detection: screenshots 33-53 ms, ML Kit
  detection 400-530 ms, about two polls per second. Every poll and anchor-miss
  reason is in `files/autoclash_events.log` (555 lines, ~50 KB). **A/C**
- The “Attack saved: victory (loot captured)” status is transient: the macro’s
  playback-complete status overwrites it. The record and loot are in the Stats
  tab and attack history; there is no separate attack text on the game screen.
  This is a minor status-message UX issue, not a data loss. **A/C**
- **Manual watch (Test 1) is now verified.** Watch start 11:42:14.679, baseline
  false 11:42:15.490, result detected 11:42:57.778, read 11:42:59.710, saved
  and record 11:43:01.554, manual watch ended 11:43:01.715. The bubble was
  hidden for the clean capture and restored; the user saw exactly that. The
  saved record is `macroName=Manual watch`, gold 802,886 / elixir 1,069,310 /
  dark 17,348, balances 18,356,125 / 17,565,448 / 321,628, confirmed and
  verified. **A/C**
- Manual capture PNG/TXT: values match; the 100% bonus panel was covered by a
  large game reward emblem, so bonus fields stayed null/`loot not captured`
  and `needsReview=true`. Gold consensus: `mlkit_full` read 802386 (wrong),
  `mlkit_crop` and `rapidocr_crop` read 802886, Tesseract/template were
  skipped; consensus chose 802886. **A**
- Stats after the manual capture: **Attacks: 7**, gold 4,964,471, elixir
  5,086,223, dark 59,363 — exactly the previous totals plus the new record.
  The three OCR records have distinct loot triples, so this is a new attack,
  not a duplicate. **A**
- The manual record's duration is the watch duration (45 s), not necessarily
  the full hand-attack duration; the record starts when “Watch results” is
  pressed. Loot and outcome are unaffected. **B**
- `macros.json` changed because the user renamed macro `New` to `Neww` during
  testing (18 gestures, once, 1.0x); the app did not modify it for this test.
  Leave it as is per the data-safety rule. **A**

## 2.4.3 repeat delay (2026-10-27)

- `Macro.repeatDelayMs: Long?` is stored in the macro JSON. `null` means the
  macro predates the setting and keeps the old **800 ms** wait; an explicit
  `0` means no extra wait. Values are clamped to 0–600 000 ms. The schema
  version is now 3. **B**
- Both edit entry points open a **Macro settings** dialog with the name and a
  decimal “Wait between repeats (seconds)” field (0–600, default 0.8):
  the dashboard pencil and the floating controller pencil. The dialog validates
  the range and saves through `MacroRepository.save`, so it is persisted in the
  macro file. There is no export/import feature yet; the field is part of the
  JSON model and will be included when that exists. **B**
- Playback waits after every iteration that has another repeat. Loop uses the
  same wait. The remaining whole seconds are published to the controller
  status (`Next loop/run in Ns`) and to the collapsed bubble (`Ns`). The
  countdown is hidden for waits below 1 s, but the wait still applies. **B**
- Stop clears the countdown immediately. Leaving Clash of Clans during the wait
  pauses playback, shows the pause reason, updates the notification to paused,
  and freezes the remaining wait; Resume continues it. **B**
- Evidence: 115 app unit tests pass, including five new/reworked cases:
  legacy null → 800 ms, explicit 0, 5 000 ms, 900 000 → 600 000, negative → 0.
  The gate tests now use a monotonic test clock so they do not flake under
  load. **A/B**
- Not yet verified: CI for 2.4.3, install, the on-device countdown, stop, and
  foreground pause. **Pending**

## Data-safety rule (user instruction, 2026-10-26)

Never modify the user's `macros.json` or `attacks.json` for a test. Use a test
copy, or ask the user first. In particular, do not delete or edit the 14 old
unconfirmed playback records; they stay untouched unless the user explicitly
approves a cleanup with a backup. `macros.json` is deliberately left as it is
(currently repeat `once` for macro `New`); do not "restore" it as part of a
test.

## 2026-10-26 user-reported failure — read-only audit

Report only: no code, phone state, app data or APK was changed for this audit.
The phone went offline before the live check (`adb devices` is empty), so
anything that needs today's logcat or the live `attacks.json` is marked
**BLOCKED**. Evidence used: the installed APK pulled at 11:56 local
(SHA-256 `1a2c04b4…d10803`), the CI artifact it matched, `attacks.json` byte
snapshots pulled at 10:32 / 11:16 / 11:48 / 11:51, the two real-attack logcats,
and the current source.

Evidence levels: **A** = verified artifact/data byte-for-byte; **B** = verified
in source; **C** = log evidence from a run; **D** = inference, not directly
observed; **BLOCKED** = phone offline.

### 1. Which APK is installed

- **versionCode 10, versionName 2.4.1.** The APK pulled from the phone has
  SHA-256 `1a2c04b43936044d219f5d3d5c017ed3bbeeca11302e4eeb962c293690d10803`
  and is byte-identical to the CI artifact from run `40379833802`
  (commit `495438a`). **A**
- It **does contain the new results reader and the phantom-record guard**. The
  DEX has `AutoClashResults`, `Baseline results screen=`,
  `Results screen detected; taking clean capture`,
  `Playback ended (interrupted=`, `loot not captured` and
  `save_results_screenshots`. **A/B**
- It **does not contain 2.4.2**: the 2.4.2-only strings `Poll at ` and
  `Detection miss: ` are absent. The installed watcher still detects on a
  half-scale frame. **A**
- Caveat: the phone is now offline; this was the last verified state at 11:56
  local. The user did not report reinstalling anything. **BLOCKED** for a fresh
  read.

### 2. Is capture running during and after playback?

- In 2.4.1 the watcher starts in `begin()` (playback start) and `finish()` only
  records the end time; it keeps polling for 4 minutes after playback. Detection
  runs on a half-scale screenshot every ~1 s plus processing. **B**
- Our own 2.4.1 run proves the start: `Baseline results screen=false` at
  `00:21:54.475`, `Playback ended (interrupted=false); watching for results` at
  `00:23:23.483`, and no `Results screen detected`. There were no screenshot
  failure logs. **C**
- 2.4.1 has **no per-poll log**, so the number of screenshots/checks taken
  during that attack is not observable. The baseline screenshot definitely ran;
  later ones are inferred from the loop, not counted. **B/D**
- The user's latest session logcat is **BLOCKED**: the phone is disconnected and
  the app does not write a persistent log file (only logcat and the
  `results_captures` folder). We cannot confirm what ran during their test.
  The last known setting was `save_results_screenshots=true` and the
  accessibility service was enabled and bound. **A (at 11:48) + BLOCKED**

### 3. Why “Rec: 0 captured · 0 rejected” and did the reader see an image?

- “Rec” is the **gesture recorder** counter, not results OCR. It is built at
  `FloatingMenuService.kt:1512` as
  `"Rec: " + capturedGestures + " captured · " + rejectedGestures + " rejected"`,
  incremented only while recording gestures (`:809`, `:1242`; rejected at
  `:1265`), and reset when recording starts (`:597`). 0/0 means no passive
  recording session was active in this service lifetime. **B**
- The loot readers (`AppResultsReader.read` → `RowCropReader`) run only after
  `isResultsScreen` returns true. In our 2.4.1 run it never did (no detection
  log), so **no image reached the multi-reader loot pipeline** and it reached no
  conclusion. The app concluded “no results screen” and wrote nothing. **B/C**
- The Stats loot line “Gold / Elixir / Dark: not captured” is the aggregate of
  the **4 old confirmed records**, all with `lootSource=none` and null values;
  `lootText` prints “not captured” when `lootedCount == 0`. It is not the result
  of a failed new read. **A/B**

### 4. Why unconfirmed rose from 12 to 14 and is the phantom fix active?

- `attacks.json` already held **18 records (4 confirmed / 14 unconfirmed)** at
  10:32 local, before any 2.4.x attack. The last two unconfirmed entries are
  `2026-10-25T20:09:57.303Z` (interrupted, 390,847 ms) and
  `2026-10-25T20:17:33.026Z` (completed, 108,914 ms), both macro `New`,
  repeat `infinite`. If the phone is on IST (UTC+5:30), those are the 01:39 and
  01:47 entries. They predate the 2.4.0 install at 10:56 local, so they were
  written by the **old build's playback-session path**. **A/D on timezone**
- `attacks.json` SHA-256 was `a2245ec2…58d404` at 10:32, 11:48 and 11:51, and
  was unchanged after both 2.4.x real attacks. The count stayed 14. **A**
- In 2.4.1 the only `attackRepository.add` call is in
  `ResultsCaptureCoordinator`; `endAttackSession` only logs and starts the
  watcher. Playback completion cannot create a record. **B**
- Conclusion: the 12→14 rise is **old data**, not a new phantom record. The
  phantom guard is active in the installed build. **A/B**

### 5. What the last hour's test did

- The durable data points to the two old-build sessions at
  `20:09Z` / `20:17Z` (01:39 / 01:47 IST): one interrupted after 6 m 31 s, one
  completed in 1 m 49 s, both with no loot and no OCR. The four confirmed
  attacks shown in Stats are older still and all have `lootSource=none`. **A**
- If the hour of testing actually happened after the 11:48 install, it left no
  new records and no new loot; the only trace would be the watcher's own
  behavior, which 2.4.1 does not log. **BLOCKED** for the live logcat.
- Our two real attacks after the install: attack 1 changed the village from
  5,702,517/3,895,944/189,102 to 7,312,999/5,537,504/203,455; attack 2 to
  8,700,806/7,002,199/212,267. Both produced **zero records and zero captures**.
  The Stats “Attacks: 4 / not captured” therefore still describes the old data,
  not these attacks. **A**
- Bottom line: the last hour did not exercise automatic loot reading
  successfully. Either it ran on the old build (most likely, given the
  timestamps) or it ran on 2.4.1 and the watcher missed the result screen
  (consistent with our two runs). The data alone cannot distinguish these
  without the phone's logcat. **D/BLOCKED**

### 6. Progress, unfinished work and known problems

Done and verified:

- Shared OCR core with strict-majority consensus, balance-math fallback, and
  template votes excluded on their four training images.
- Lab timing on four result screens: cheap-first 3.12 s compute / 3.62 s wall
  total (0.61-1.01 s per screen); all-readers 14.66 s corrected compute.
  4/4 results, 0 wrong, 0 missing.
- App integration in 2.4.1: Accessibility screenshots, watcher from playback
  start, one-record-per-detected-result, null-not-zero, PNG + JSON/TXT sidecar
  writer, debug screenshot switch. CI green for 2.4.0 and 2.4.1; 2.4.1 APK
  installed and byte-verified.
- Phantom guard verified: two real attacks, no phantom records; the only
  `add` path is the results coordinator.

Unfinished:

- **No real result screen has been captured by the app.** 2.4.1 missed both
  real attacks; the multi-reader loot path has never run on an app-captured
  real screen.
- 2.4.2 (full-resolution detection, no inter-poll delay, per-poll logs) is
  committed and pushed as `77ea41e`, but GitHub lists **no workflow run** for
  that commit yet and it is **not installed**. **A/blocked by no run**
- The 30+ real-capture set and a non-training accuracy score are still pending.
- `macros.json` is still modified from testing: the `New` macro's repeat is
  `once` instead of the original `infinite` (original backup
  `.ocr-work/macros.before.json`, SHA-256 `e0f76df0…60ad6`). It was **not**
  restored in this report-only pass.

Known problems / risks:

- The 2.4.1 detector uses a half-scale frame; `You got` is about 10 px tall and
  is the leading suspect for the missed detection. This is not yet proven.
- The result screen can be dismissed within a second or two by the macro's
  Return Home tap (fixed at 80.2 s); 2.4.1 also waits 1 s between polls and has
  no per-poll evidence.
- The Stats “Rec: … captured/rejected” label is about gesture recording, not
  loot capture; this wording caused a reasonable misreading.
- The 14 old unconfirmed playback sessions remain in the history and inflate
  the “unconfirmed” count; they are not new attacks.
- The phone is offline, so no fresh logcat/data check is possible until it is
  reconnected.

## Digit-template provenance

The digit templates were built from the **four labelled result screens inside
the 11-image lab pack**:

- `08_results_victory_2star_70.jpg`
- `09_results_defeat_35.jpg`
- `10_results_victory_3star_100.jpg`
- `11_results_victory_2star_71_reference.jpg`

They were not built from the seven non-result frames. The extraction produced
24x32 neutral-channel glyph masks, eight samples per digit. A host-side
leave-one-row-out check matched 199/199 glyphs; that is a template-design
check, not a device accuracy score.

Scoring rule now enforced in code: the template classification is **excluded
from both consensus and per-engine scoring** on those four images. The
template segmentation step is still used to bound the row crop, but it does
not contribute a digit vote. Template accuracy is only meaningful on captures
that were not training images.

## Reader pipeline and speed strategy

Ranked untouched.

Cheap pass:

1. Full-page ML Kit (already required for results-screen detection).
2. Crop ML Kit on the tight number line.
3. Tesseract on the inverted crop.

If the cheap readers have a strict-majority consensus, the row stops there.
RapidOCR runs only when the cheap pass cannot agree. Digit templates run only
when the cheap+Rapid pass still cannot agree. The template segmentation step
still runs first because it supplies the crop geometry, but template
classification is skipped unless needed and is never used on training images.

Consensus requires at least two readers and a strict majority of all readers
with numeric evidence. A lone reader is accepted only when the exact balance
identity independently predicts the same value. Balance math is available in
the lab when a defeat→victory sequence and the search cost are present.

Timing instrumentation is in the lab report: per-reader total/screen/row times,
page OCR time, consensus time, crop-save time, and wall/compute time per result
screen. The app reuses the cheap-first strategy but saves only the full result
screenshot, not per-row crops.

Measured on the phone with the four result screens and no crop saving
(`strategy=all` before, `strategy=cheap` after):

- All readers: 40 row reads, ML Kit full 1,124 ms, crop 1,899 ms, Tesseract
  750 ms, RapidOCR 1,155 ms, template segmentation 212 ms, template
  classification 9,520 ms after removing a 438 s OS freeze artifact, consensus
  <1 ms. Corrected compute 14.66 s total, about 3.7 s per screen.
- Cheap first: RapidOCR and template classification were skipped on all 33 rows.
  ML Kit full 775 ms, crop 1,470 ms, Tesseract 670 ms, template segmentation
  207 ms, consensus <1 ms. Compute 3.12 s total (1.01 / 0.61 / 0.77 / 0.74 s
  per screen); wall 3.62 s total (1.19 / 0.68 / 0.88 / 0.87 s per screen).
- Both strategies: 4/4 result screens, 0 false positives, 0 wrong captures,
  0 missing values, consensus 37/37. Template votes were excluded on these
  four training images.

## App integration

- Accessibility is used for capture: `AccessibilityService.takeScreenshot`
  (Android 11+), with `android:canTakeScreenshot="true"` in the service config.
  Shizuku remains the input/getevent transport; it is not used for capture.
- Playback start captures a baseline screenshot and checks it for result-screen
  anchors. If the results screen was already open before playback, it is treated
  as stale and no record is created.
- The results watcher starts at playback start and runs until four minutes
  after playback ends (hard cap 15 minutes). Watching only after playback is
  wrong: the first real run proved that the macro taps Return Home while the
  result screen is open, before playback finishes. The bubble stays usable
  while polling. Detection uses the full-resolution screenshot (2.4.2); the
  half-scale frame in 2.4.0/2.4.1 shrank "You got" to about 10 px and is the
  likely reason the second attack was missed. Polls have no artificial delay,
  so the loop runs at screenshot+OCR speed. Once the anchors appear, the bubble
  is hidden for a clean full-resolution capture, then restored. If the clean
  frame no longer shows results (for example because the macro tapped Return
  Home during the delay), the detection frame is read instead.
- Race gate (2.4.2): every tap in the last 15 s of a macro waits for a fresh
  detection sample (up to 2 s, including an in-flight poll). If that sample
  shows a result screen, the tap is held until the watcher has captured and
  read it, for at most 8 s (about 10 s including the fresh-sample wait), then
  the tap continues. This protects the Return Home tap that would otherwise
  dismiss a screen that was visible for about a second. The gate adds little or
  no delay when the watcher's latest sample is fresh (<500 ms old).
- Manual watch (2.4.2): Stats → “Watch results” starts the same watcher without
  a macro, for a hand-played attack. It creates a record named “Manual watch”
  only when a result screen is detected; it never creates a record on start or
  stop. It runs for up to 15 minutes and can be stopped from the same button.
- Persistent event log (2.4.2): `files/autoclash_events.log` in the app's
  private folder, capped at 256 KB with one rotated copy. It contains playback
  start/end, manual-watch start/stop, every poll's shot/detect time and
  anchor-miss reason, read field presence (not values), save paths, record id
  and errors. It contains no screenshots, loot values or macro names. Pull it
  with `adb exec-out run-as com.clahbot cat files/autoclash_events.log`.
- A detected result screen creates exactly one confirmed attack record. Playback
  completion alone never writes a record. The existing `AttackRecordPolicy`
  gate is enforced in the coordinator.
- The app stores null for missing values. The history UI now shows `—` for a
  missing field inside a partially captured record and "Loot not captured" when
  no loot was read. It never substitutes 0 for a missing value.
- "Save results screenshots" (Settings → Results capture (debug)) writes the
  result PNG plus a JSON and TXT sidecar under the app's external
  `results_captures` folder. The sidecar contains the app's own read and the
  per-reader evidence. A failed loot read is written as "loot not captured".
  Nothing is uploaded.
- Existing `macros.json` and `attacks.json` are not rewritten by the build or
  by installing the update. A new attack record is written only after a real
  detected result screen. The repository schema is now 3, with new nullable
  `outcome`, `resultsScreenshot`, and `resultsSidecar` fields.
- The current app APK is arm64-only (58 MB debug) because the OCR native
  libraries made the universal APK about 151 MB. The lab APK is also arm64-only
  (50 MB). This is a deliberate limitation of this build.

## Evidence ledger

| Item | Evidence | Level | Still unverified |
|---|---|---|---|
| 1. Template provenance + exclusion | Exact source images recorded above. Template classification is excluded from votes/scores on 08-11 in code. The earlier 0/0 run included templates, so it is not counted. The current-rule four-screen timing run scored 0 wrong / 0 missing with template votes excluded. | Code + source evidence + current-rule device run on 4 training images | A non-training accuracy score (real captures) |
| 2. Speed before/after | Four result screens, no crop saving: all readers 14.66 s compute (3.7 s/screen) versus cheap-first 3.12 s compute (0.61-1.01 s/screen) and 3.62 s wall. Per-reader tables are in "Verified numbers". RapidOCR and templates were skipped entirely by cheap-first. | Automated-device measurement with the lab instrumentation | Timing with the app's own warm-up and a real results screen |
| 3. App reader integration + phantom fix | 2.4.2 `1c16d90` installed with `attacks.json`/macros data handled per the safety rule. Three real result screens were captured (two macro playbacks, one hand-played Manual watch), read, saved and recorded; values match the PNGs and Stats sums; the race gate held the Return Home tap until after the save. The persistent poll log and gate unit tests are green. | On-device artifact (A), records/sidecars (A), gate timing (C), unit/CI (A) | Stale-result rejection in the app; 30+ captures |
| 4. “Save results screenshots” setting | Setting enabled on the phone; three PNG + JSON + TXT sidecars produced. One sidecar shows a real consensus where ML Kit full was wrong and three other readers agreed; another shows the same on the manual capture. Missing bonuses were written as null/`loot not captured`, never 0. | On-device PNG/sidecar verification (A) | 30+ capture collection and scoring |
| 5. AGENT_CONTEXT.md | This file | Updated with the real-capture evidence, build identity, and the 2.4.3 repeat-delay work | Final evidence for 2.4.3 on-device |
| 6. Repeat delay (2.4.3) | Nullable `repeatDelayMs` per macro with a legacy 800 ms fallback; edit dialog in both UIs; player countdown, foreground pause, stop-cancel; five unit cases and 115 total green | Unit/CI-level (A/B) | CI run; on-device countdown, stop and foreground pause |

## Verified numbers

Speed, four result screens (`08`-`11`), crop saving off, templates excluded
from votes and scoring:

| Run | Test time | Compute | Wall | Per-screen compute | Per-screen wall |
|---|---|---|---|---|---|
| All readers | 455.4 s raw* | 14.66 s corrected | — | 3.7 s | — |
| Cheap first | 4.485 s | 3.12 s | 3.62 s | 0.61-1.01 s | 0.68-1.19 s |

\* The raw 455.4 s contains a 438 s OS freeze on screen 08 (phone was in use).
The corrected compute removes that single outlier. Reader totals in the raw
report were: ML Kit full 1,124 ms, crop 1,899 ms, Tesseract 750 ms, RapidOCR
1,155 ms, template segmentation 212 ms, template classification 447,870 ms raw
/ 9,520 ms corrected.

Accuracy, same four screens:

- All readers: 4/4 result screens, 0 false positives, 0 wrong captures,
  0 missing. On the 33 field rows: ML Kit full 33/33, crop 33/33, Tesseract
  25/33 (4 wrong, 4 missing), RapidOCR 32/33 (1 wrong balance), consensus
  33/33; consensus also read 4/4 outcomes.
- Cheap first: 4/4 result screens, 0 false positives, 0 wrong captures,
  0 missing. On the 33 field rows: ML Kit full 33/33, crop 33/33, Tesseract
  25/33, consensus 33/33 plus 4/4 outcomes; RapidOCR and templates were
  skipped on every row.

Initial v0.4 11-image run (templates included, therefore not a valid score
under the current rule): 4/4 results, 11/11 classification, 0 false
positives, 0 wrong, 0 missing; 578 s wall. That run included startup, model
loading, full-page OCR of all 11 frames, every reader and crop saving.

Real attacks:

- 2.4.0, first run: 5,702,517 gold / 3,895,944 elixir / 189,102 dark before;
  7,312,999 / 5,537,504 / 203,455 after. `attacks.json` unchanged and no
  `results_captures` folder. Log: playback end then baseline false; no
  detection. The results screen was dismissed by the macro's Return Home tap at
  80.2 s, before the 2.4.0 watcher started.
- 2.4.1, second run: 7,312,999 / 5,537,504 / 203,455 before; 8,700,806 /
  7,002,199 / 212,267 after. `attacks.json` still unchanged and no captures.
  Log: baseline false at 00:21:54, playback end at 00:23:23, no detection.
- Both runs: the phantom-record guard worked (playback completion created
  nothing). Neither run produced data on where detection failed; 2.4.2 adds
  per-poll logs and the parser's anchor-miss reason.

## Build and test commands used

Gradle 8.9 was used locally because the wrapper’s 8.2 distribution was not
cached; CI uses the repository wrapper (8.2).

- Unit tests: `:app:testDebugUnitTest :ocrlab:testDebugUnitTest` — app 108
  tests, lab 36 tests, both green at the time of writing.
- APKs: `:app:assembleDebug`, `:ocrlab:assembleDebug`,
  `:ocrlab:assembleDebugAndroidTest` — build successfully.
- CI run `40301269207` for commit `78fd535` was green (unit tests, lab APK,
  test APK, uploads).
- CI run `40368076334` for commit `ebd0dec` (shared `:ocr` library and app
  integration) was green: unit tests, app/lab debug APKs, lab test APK and
  uploads.
- CI run `40371851457` for commit `34f398e` (the 4-minute watcher and clean
  capture) was green: unit tests, app/lab debug APKs, lab test APK and uploads.
- 2026-10-26: local `:app:compileDebugKotlin :ocrlab:compileDebugKotlin` and
  `:app:testDebugUnitTest :ocr:testDebugUnitTest` passed with Gradle 8.9 and
  JDK 17 after the watcher-start fix.
- CI run `40383387019` for commit `77ea41e` (2.4.2 full-resolution detection
  and poll logging) was green. The race-gate/log/manual-watch additions are
  built and unit-tested locally (including 3 new `ResultCaptureGateTest`
  cases); their CI run is pending. The earlier `head_sha` query that returned
  zero runs was a GitHub indexing delay, not a workflow problem: the workflow
  triggers on `push` to `main` with no path filter and is active.
- Lab benchmark commands used on the phone:
  `adb shell am instrument -w -e strategy all -e subset results -e saveCrops false -e class com.clahbot.ocrlab.OcrLabAccuracyTest com.clahbot.ocrlab.test/androidx.test.runner.AndroidJUnitRunner`
  and the same with `strategy` omitted for cheap-first. Reports pulled to
  `.ocr-work/speed_before.json` and `.ocr-work/speed_after.json`.

## Next actions

1. 2.4.3 is implemented and unit-tested. Build it in CI, then ask the user
   before installing it.
2. On-device 2.4.3 test, only after approval: set a 5 s wait on a macro and
   verify the countdown, Stop cancelling it, and the foreground pause. This
   edits `macros.json`, so ask first or use a test copy.
3. Do not modify `macros.json` or `attacks.json` for a test; leave the 14 old
   unconfirmed records untouched. A “clear old unconfirmed sessions with
   automatic backup” option is a possible later UI change, only after the user
   approves it.
4. Turn on “Save results screenshots” and collect the 30+ real result screens.
   Score them as a fresh (non-training) set, keeping template votes for those
   real captures.
5. Do not start product Features 2-5 without the user’s explicit approval.

## Known limitations / risks

- Templates are trained on the four lab result screens; any score on those
  screens without the exclusion rule is invalid.
- The 11-image pack is not a clean holdout; it informed every fix.
- Full-page and crop ML Kit are correlated evidence; the strict majority plus
  RapidOCR/templates is the guard. A correlated ML Kit pair can still agree
  incorrectly when only one other reader has evidence.
- Accessibility screenshot capture requires Android 11+ and the
  `canTakeScreenshot` service flag. The service may need to be re-enabled after
  the update so the new service config is loaded.
- The current build is arm64-only. Other phone ABIs are not packaged.
- OCR can miss fields; the app records nulls and marks them, and the user can
  correct them from attack history.
- The results watcher polls continuously during playback and for up to 4
  minutes after it. In 2.4.2 detection OCR runs on the full-resolution frame,
  so the loop rate is bounded by the screenshot plus OCR (about 1 s on this
  phone). This has not been battery- or heat-tested during a long attack.
- The race gate applies to taps in the last 15 s of a macro and can add up to
  2 s before such a tap when the latest detection sample is stale. If the
  result screen is already visible, the tap is held for up to 8 s (about 10 s
  including the fresh-sample wait) while the capture and read finish. A human
  hand tap on a manual watch cannot be held.
- Manual watch creates one record named “Manual watch” when a result screen is
  detected. It runs for up to 15 minutes; the Results button stays available
  on the Stats tab. A human tap is not held, so the result screen must be left
  open until the save completes. The record’s duration is the watch duration,
  not the full hand-attack duration.
- The event log is capped at 256 KB plus one rotated copy; it omits loot
  values, macro names and screenshots.
- The macro captures showed an empty grey bonus panel; the manual capture had a
  large game reward emblem over the bonus panel. The app correctly stored the
  bonus fields as null/`loot not captured` and set `needsReview`; the three
  main loot values were verified in all three captures.
- “Attack saved: victory” is a transient status and is overwritten by
  “Playback complete”; the record and loot remain in Stats and attack history.
- Some watcher screenshots failed briefly during the battle at 74-75 s (logged
  as `screenshot failed`); the watcher recovered and captured the result
  screen. This needs monitoring on longer battles.
- The user renamed macro `New` to `Neww` during testing, so `macros.json`
  changed (hash `57367428…`). This was the user’s edit; do not revert it.
- Repeat delay: the countdown is hidden below 1 s (the wait still applies);
  it is per macro and not scaled by playback speed. Legacy macros have a null
  field and keep the 800 ms behavior until edited; editing and saving writes an
  explicit value. The on-device test that changes the delay will modify
  `macros.json`, so it needs the user’s approval first.
- The results screen may be visible for only a few seconds before the macro
  taps Return Home (about 4 s in the first real run). Detection uses a 1 s
  poll; if the clean capture lands after the tap, the retained detection frame
  is read instead. If both fail, no record is written rather than a fake one.
- Changing speed or repeat in the controller persists those playback settings
  into `macros.json` for the selected macro (existing behavior, not new). The
  verification changed the `New` macro's repeat; the original file is restored
  from backup after testing.
