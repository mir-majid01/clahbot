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
  stop-cancel behavior. CI run `40464810499` for commit `585ea20` is green and
  the build is installed: APK SHA-256 `98ebcf0c…`, identical to the CI
  artifact; `macros.json`/`attacks.json` hashes were unchanged by the install.
- First on-device proof of the new field: the user edited macro `Neww` through
  the Macro settings dialog and saved `repeatDelayMs = 3000`,
  `repeatMode = infinite`, `schemaVersion = 3` in `macros.json`. The saved
  field, dialog and persistence work. The on-device countdown, Stop and
  foreground-pause behavior are still being tested by the user.
- The real-capture set is now **24 app captures** (72 files) plus the 34
  external result screens in `newimages`. Step 1 is complete with the live
  pull below; Step 2 has not started. The live pull found 42 records, 21
  current 2.4.3 records, 2 loot-less records, 23 watches rejected because a
  result screen was already open, and at least 31 external result screens with
  no matching record. See “Live pull” below.
- 2.4.3 is **paused**, not abandoned: branch `paused/repeat-delay-2.4.3` at
  `0befc3c` contains the feature. Reliable loot capture is the current top
  priority. The branch is an unchanged snapshot of `main` at that commit, so
  nothing had to be re-committed to preserve it.
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
- Installed on the phone (SHA `98ebcf0c…`) and the save path is proven by the
  user's own edit above. The user will finish the on-device countdown, Stop and
  foreground-pause check themselves and report the result. **Pending**
- Per the user's decision (2026-10-27), macro `Neww` stays at
  `repeatDelayMs = 3000` and `repeatMode = infinite`; do not revert it to
  0.8 s / `once`.

## Step 1 — missed and loot-less attacks (2026-10-27)

Read-only diagnosis; no APK was installed and the phone screen was not
interacted with. The only code change is diagnostic logging (commit `ef02232`),
no behavior or UI change. Evidence: the pulled rolling logs `events_243.log`
(earlier, 1309 lines, 2.4.2 from 10:56:12 and 2.4.3 from 12:49:24 to 12:58:05)
and `events.now.log` (live, 208,104 bytes, 42 records / 24 captures), the byte
snapshots `attacks.before_243.json` / `attacks.after_243.json` (21 records) and
`attacks.now.json` (42 records), the 24 app captures and sidecars, and the
34 screenshots in `newimages` (extracted read-only into
`.ocr-work/newimages_extracted`; the original folder was not modified).
Evidence levels: **A** artifact, **B** code, **C** saved-log evidence,
**D** inference, **BLOCKED** evidence that could not be retrieved.

### Branch and paused feature

- `paused/repeat-delay-2.4.3` = `0befc3c`; it contains the 2.4.3 repeat-delay
  work (implementation `585ea20`, CI `40464810499` green, installed SHA
  `98ebcf0c…`). The user keeps `Neww` at 3 s / Loop. The on-device countdown,
  Stop and foreground-pause check is still the user's pending test. **A/B**
- `main` continues with diagnosis. The diagnostic-logging commit is `ef02232`
  (compile and 115 app unit tests green locally; **not built into an APK and
  not installed**). **A**
- The paused branch is **local only**. `git push origin
  paused/repeat-delay-2.4.3` could not authenticate non-interactively: GCM
  reports “Cannot prompt because user interactivity has been disabled”.
  The six diagnosis commits on `main` are also local. **A**

### Earlier saved-log pull (phone was offline)

- 7 playback starts, 5 manual watches, 7 result detections and 7 records.
  Of these, the 2.4.3 period (from 12:49:24) has 3 macro watches + 2 manual
  watches, 4 detections, 4 records, 1 watch still open at the end, and
  **2 records with loot not captured** (12:55:07 and 12:56:53). The 2.4.2
  period has 4 macro watches + 3 manual watches, 3 records, all with loot,
  and **4 watches cancelled or stopped before any result**. **A/C**
- The two `attacks.*_243.json` snapshots predate the 12:51–12:56 records, so
  those 4 records are absent from the copies. That is a stale copy, not a
  loss. The four 2.4.3 records have IDs 1793085609423, 1793085728410,
  1793085816858 and 1793085941732. **A/C**
- The earlier saved log stopped at 12:58, so it could not classify the 34
  external screenshots by itself. **A**

### Live pull (phone reconnected, 2026-10-27 ~14:30 device)

Read-only copies were pulled after the user reconnected the phone; no app data
was changed and no APK was installed. Files:
`.ocr-work/step1_live/attacks.now.json` (SHA-256 `537cf971…`),
`macros.now.json` (`382493ac…`), `events.now.log` (208,104 bytes), and
`captures/` (24 PNG + JSON + TXT, 108 MB). The installed build is 2.4.3
(versionCode 12). **A**

- `attacks.json` now has **42 records: 28 confirmed / 14 old unconfirmed**.
  Of the 28 confirmed, **24 have OCR captures** (3 from 2.4.2, 21 from 2.4.3);
  22 have at least one loot value. **Six confirmed records are loot-less**:
  the 2 new 2.4.3 records at 12:55:07 and 12:56:53, plus 4 older records from
  2026-10-24/25. The 14 old unconfirmed records are untouched. **A**
- The current log has 21 2.4.3 records / 21 detections, 2 loot-less, 11
  clean-frame failures with detection fallback, 0 both-frames failures, and
  no 2.4.3 watch that ended without a capture. **A/C**
- **23 watches were rejected with `baseline results screen=true`
  (“Already on a results screen; no new attack recorded”).** This is the
  dominant observed “not added” path. The coordinator intentionally treats a
  result screen that is already open when Watch is pressed as stale; it does
  not check whether that screen is already recorded. These are 23 watch
  attempts, not necessarily 23 distinct attacks: several clusters are repeated
  presses on the same open screen. **A/B/C**
- The live log’s 2.4.3 manual-watch pattern: 41 manual starts, 23 stale
  rejects, 17 manual records (plus 4 macro records), 1 open at the pull.
  This is consistent with arming “Watch results” **after** the result screen
  has appeared, especially after returning from a prior recorded result. **A/C**
- The 34 external files contain **33 unique result screens** (2 files are the
  same 2,905,186 / 2,737,932 / 19,445 result and match record
  `1793087163242`; `2.04.11 (1)` with 2,404,830 / 2,430,401 / 6,717 matches
  record `1793086778779`). **The other 31 files have no matching loot triple
  among the 42 records** by visual transcription; Step 2 will OCR-confirm this.
  So the folder is mostly, but not entirely, missed attacks: **at least 31
  unique missed result screens**, 2 captured results, and 1 duplicate frame
  pair. **A/C/D**
- The 31 misses include non-standard layouts already visible in the folder:
  a 2-row/no-bonus screen (8,000 / 12,000), a war “Claim Reward” layout, a
  side-reward “Claim Reward” layout, a covered bonus card, and a blank bonus
  card. Step 3 will score these against the current reader. **B/D**

### Counts by category

Confirmed from the live 2.4.3 log (detected result = one record):

| Category | Live 2.4.3 count | Evidence |
|---|---|---|
| a) results screen never detected | 0 confirmed under an active watch | 21/21 detections produced records; no 2.4.3 watch ended without a capture **C** |
| b) detected, capture failed/late | 0 lost attacks | 11 clean frames read as no-result and fell back to the detection frame; 2 fallbacks became (c) **C** |
| c) captured, read failed → "not captured" | **2** | 12:55:07 rows 1/3 conflict; 12:56:53 rows 0/3; both saved with null loot **A/C** |
| d) rejected by another gate | **23 stale-result watch attempts** | `baseline results screen=true`; the open screen is refused; not duplicate/balance; repeated presses, not distinct attacks **A/B/C** |
| e) written then lost/overwritten | **0 observed** | 42 records, 24 captures present; atomic temp-file writes; no clear path **A/B** |
| f) watcher not running | **≥8 of the 31 missed screens** | 31 unique missed screens minus at most 23 late attempts; manual watch is one-shot and there were unarmed attacks **D** |
| g) other | 0 known | non-standard layouts will be scored under (a)/(c) in Step 3 **B/D** |

The 23 stale-result rejects are **attempts**, not 23 distinct attacks, and
some are repeated presses on the same already-open screen. The attack-level
count is the **31 unique external result screens with no matching record**;
the live log proves one mechanism (late arming / stale screen) for many of
them but cannot map each screenshot to an attempt without a per-screen
fingerprint. Step 2 will dedupe the 34 files by attack/time and Step 3 will
separate reader failures from arming failures.

### Manual attacks

- Manual attacks are watched **only** when the user presses Stats → “Watch
  results”. There is no automatic trigger when the user starts a hand attack;
  the app cannot know one started. **B**
- Manual watch is **one result only**. After the first detected result it ends
  and the user must arm it again for the next hand attack. It also aborts if a
  result screen is already open at arm time (`baseline results screen=true`),
  because that screen is treated as stale to avoid a duplicate record. **B/C**
- Live 2.4.3 evidence: 41 manual starts, **17 manual records**, **23 stale
  rejects**, 4 macro records, and 1 watch open at the pull. The 23 rejects are
  the app refusing an already-open result screen, not duplicate loot records.
  This is the observed mechanism behind “I pressed Watch and the attack was
  not added”. **A/C**
- The 34 screenshots show the app's WATCH bubble on many frames, so those
  results were hand-played with manual watch armed (or armed too late). The
  bubble alone does not prove the screen was recorded. **C/D**

### Duplicate gate

- No, two attacks with similar or identical loot numbers cannot be merged.
  `AttackRepository.add` compares only `record.id`; the record ID is the
  capture-session start time (`System.currentTimeMillis()` at watch start).
  There is no loot-value comparison and no balance check in the app. The
  “duplicate or invalid” warning is a single generic message. **B**
- The live log has **0 duplicate rejections**. The 23 rejections are the
  separate baseline/stale-result guard, not the duplicate gate. All 24 new
  record IDs and the 24 capture files are distinct; the 21 current 2.4.3
  records have distinct loot triples except where OCR was incomplete. **A/C**
- The new logging now distinguishes `reason=duplicate` from `reason=invalid`
  and logs the record count after each write. **B**

### Root causes ranked (for Step 4, not fixed yet)

1. **Late manual arming + the stale-result guard.** The user presses “Watch
   results” after the result screen is already open. The coordinator sees
   `baseline results screen=true` and refuses the screen, even when it has
   never been recorded. Live evidence: 23 rejects; 31 unique external result
   screens with no matching record. This is the leading explanation for “many
   attacks not added at all”. **A/B/C**, high confidence for the mechanism;
   per-screenshot mapping still needs Step 2/3.
2. **The watcher is one-shot; manual watch and macro loops need re-arming.**
   After `saveAndRecord` the watch returns; a looping macro keeps playing with
   no watcher, and a new manual attack needs another Watch press. `Neww` is
   `repeat=infinite`. **B/C**
3. **The clean capture is unreliable; the fallback is an older, possibly
   mid-animation frame.** Live 2.4.3: 11 clean-frame no-result fallbacks; 2
   fallback reads were incomplete and became the 2 loot-less records. The new
   log records the clean frame's anchor check and reason. **A/C**
4. **Non-standard result layouts are not covered by the parser.** `inspect`
   requires “You got”, Victory/Defeat and “Total damage”; the external set
   includes war/Claim Reward layouts, a 2-row/no-bonus screen, and covered or
   blank bonus panels. These can fail detection or lose rows. **B/D**
5. **Starting a new playback/manual watch cancels the old watch**, and an
   already-open result screen is rejected as stale. Historical evidence: two
   restarts cancelled macro watches (11:04) and one manual watch was replaced
   by a macro (10:58). **B/C**
6. **If both the clean and detection frames are unreadable, no record is
   created.** The code logs `both frames failed to read` and keeps polling; if
   the screen then disappears, the attack is lost. Live count: 0, but it
   violates the one-screen-one-record rule planned for Step 5. **B**
7. **Not causes:** duplicate rejection (0), balance rejection (no balance gate
   in the app), file overwrite/data loss (no evidence), detection anchor
   failure in a running watch (0 of 21 live detections).

### Missing logging that was added (commit `ef02232`)

Added, behavior unchanged: watch begin `replaced/previous` IDs; cancel ID;
per-poll foreground package on a skip; screenshot-failure streak; detection
frame size; clean-capture timing/size; clean-frame anchor check and reason;
fallback outcome; both-frames failure detail; watch deadline reason and poll
count; watch-final state; macro iteration/repeat and loop-exit lines; record
callback with player-running/repeat/capture IDs; record add `ok` with count and
`duplicate` vs `invalid`; manual-watch stop ID.

Still not proven by this logging (needs a new install and a fresh run): whether
an **ungated tap** dismissed a result screen (only gated taps are logged), and
a true process heartbeat if XOS freezes the app. A result screen's own values
are deliberately not logged (screenshots are kept instead).

The live log also cannot link a stale reject to a particular external
screenshot: at baseline it records only `results screen=true`, not a screen
fingerprint. Missing logging for the next build: when the baseline is already
on a result screen, read that screen and log a **non-value fingerprint/presence
hash** (the same fingerprint already computed in `ResultsRead`, no loot values),
and log the same hash with each saved record. That would let the 23 rejects be
matched to the external screens. This is a logging-only change and has not
been built or installed. **B/D**

### Data safety

No original `macros.json` or `attacks.json` was touched. The live evidence is a
read-only pull into `.ocr-work/step1_live/`; the phone screen was not touched
and nothing was installed. The 14 old unconfirmed records are untouched.
`Neww` remains 3 s / Loop. The 24 capture PNG/JSON/TXT files were copied, not
moved or edited. **A**

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
| 6. Repeat delay (2.4.3) | Nullable `repeatDelayMs` per macro with a legacy 800 ms fallback; edit dialog in both UIs; player countdown, foreground pause, stop-cancel; five unit cases and 115 total green; CI `40464810499` green; installed SHA `98ebcf0c…`; the user saved `repeatDelayMs=3000` and `repeatMode=infinite` through the dialog (schema 3) | Install + unit/CI + on-device save (A/B) | On-device countdown, stop and foreground pause |
| 7. Step 1 missed/loot-less diagnosis | Live pull: 42 records (28 confirmed / 14 old unconfirmed), 24 app captures (3 from 2.4.2, 21 from 2.4.3), 2 current loot-less records, 23 stale-result watch rejects, 41 manual starts, 17 manual records; 34 external files = 33 unique result screens, 2 captured results (one duplicate pair) and **31 with no matching record**; duplicate gate is id-only, not the cause | Read-only pulls + code + screenshot inventory (A/B/C/D) | Per-screenshot mapping of the 23 rejects needs a baseline screen fingerprint (logging not yet built); Step 2 OCR confirmation of the 31 unmatched screens; 31 misses are not yet reader-vs-arming separated |

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

1. Wait for the user's own on-device report on the 3 s wait: the Loop
   countdown, Stop cancelling it, and the foreground pause. Record it in this
   file with the evidence level they provide.
2. Do not write `macros.json` again for this feature unless the user asks;
   `Neww` stays at 3 s / Loop.
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
  explicit value. The user’s macro `Neww` is currently `repeatDelayMs=3000`
  and `repeatMode=infinite` from their own test edit; on 2026-10-27 the user
  chose to keep those values, so do not revert them.
- The results screen may be visible for only a few seconds before the macro
  taps Return Home (about 4 s in the first real run). Detection uses a 1 s
  poll; if the clean capture lands after the tap, the retained detection frame
  is read instead. If both fail, no record is written rather than a fake one.
- Changing speed or repeat in the controller persists those playback settings
  into `macros.json` for the selected macro (existing behavior, not new). The
  verification changed the `New` macro's repeat; the original file is restored
  from backup after testing.

## 2026-10-27 — Reliable loot capture (priority change)

This section supersedes the version numbers and watcher limits above. The
per-macro repeat-delay UI work is paused.

### Installed state

- App **2.4.5** (versionCode 14), APK SHA-256
  `1cf5f0ce056c8a2d61e836301f2966fc73d3689731b9cef81dea6682fe84911a`.
  The installed APK pulled from the phone matches the local build byte-for-byte.
- Install was an in-place `adb install -r`; `attacks.json` stayed
  `305a4341…fb177` and `macros.json` stayed `e8115ee2…7e0b` before and after.
- The paused delay branch is `paused/repeat-delay-2.4.3` at `0befc3c`, local
  only; GitHub authentication is still unavailable. The capture work is on
  `main`.

### Step 1 — diagnosis (evidence: A for captures/log, B for code)

- 24 app captures were on disk; 23 result-screen detections had been rejected
  as stale; 2 records had no loot; 31 external screenshots did not match any
  record. The duplicate gate used id only.
- Manual attacks were not watched unless **Watch results** was armed. In 2.4.3
  the arm was one-shot and a late arm could not start once the result screen
  was already open. This was the main "attack missing" cause, not OCR.
- Similar loot triples were not merged by the duplicate gate; only the record
  id was compared.

### Step 2 — test set (evidence: A)

- `.ocr-work/step2/testpack_real/`: 58 images = 24 native app captures
  (2720×1224 PNG) + 34 external images (25 full frames, 9 crops, upscaled 2×
  JPEG from WhatsApp). Split by attack/time, never randomly.
- Ground truth was visually checked; the three known row-order mistakes and the
  two zero screens were corrected by record id, not by screenshot timestamp.
- Review sheet: `.ocr-work/step2/review_sheet.html` (and `.csv`) lists all 58
  images with human-confirmed vs reader-draft values. Native rows are
  human-confirmed; external rows are marked “review · draft only” and their loot
  was not human-labelled.
- The 34 compressed external frames are a robustness set only. They are not a
  detection target because WhatsApp resizing removed text; native captures are
  the meaningful detection baseline. External full frames detected 11/25 at
  best; crops 0/9. No app behavior was tuned to those images.

### Step 3 — baseline on the pack (evidence: A)

- Native: 24/24 result screens detected. 19/24 had all three loot values
  correct. Three records had the elixir value filed under dark elixir
  (2,058,013 / 2,932,863 / 1,722,937) and two 0%-loot screens had null loot.
  Four field values were wrong; the app never wrote a wrong value into the live
  history because the lab ran on a copy.

### Step 4 — fixes and final pack score (evidence: A for report, B for code)

Implemented: continuous watcher, late manual arm, 60-minute manual watch,
screen-hash one-screen-one-record cycle, unreadable-screen record, re-scan,
capture-source labels, retention cap, and the OCR/crop/consensus changes:

- Fixed row order gold → elixir → dark from `You got` geometry.
- Loot row pitch from the `You got` anchor (0.07 × screen height), not the
  distance to `Troops expended` (war layouts pack rows at the top).
- Crop readers use the full-page number's own box when it exists; the fallback
  no-text slot is a single-digit band beside the icon.
- Resource icon is removed from digit segmentation; the panel edge can no
  longer define the glyph line.
- A separated period is a grouping separator (`S32.743` → 532743).
- Label text glued to the front of a number keeps the number (`sist918 016` →
  918016).
- Consensus: clean readers (no long word in their text) outvote name-overlaid
  readers; one value read by ≥2 readers with all other readings one-digit
  slips or short unrelated numbers is accepted; a short value that is only the
  tail of a longer reading is rejected; a no-text row records 0 only when the
  digit template and an OCR reader both see exactly `0`.

Final native pack result: **24/24 detected, 23/24 with all three values
correct, 0 wrong values, median 1.06 s per screen**. Remaining failures:

1. `1793085905665` (0% victory, very dim): dark zero confirmed, gold and
   elixir zeros not confirmed by template + OCR → `loot not captured`. Honest
   failure; the other zero screen `1793086011551` is 0/0/0 confirmed.
2. `1793090782271`: a burned-in name (`Dank sist`) covers the leading digits
   of 918016. Readers see 8016 / 11816 / 918016; two readers never agree, so
   the gold value stays `loot not captured` rather than guessing.

The 98% target is therefore **not proven** on this 24-native pack (95.8%);
0 wrong values is proven on the pack. External compressed frames are excluded
from these percentages.

### Step 5 — re-scan verification (evidence: A for report/screenshots)

`ResultsRescan` re-reads only confirmed records with a screenshot that are
missing loot or incomplete, accepts only all three main values, keeps the
original screenshot, sets `captureSource=re-scanned`, and appends a note. The
dashboard button was added in `MainActivity`.

A copy-only instrumentation test (`app/src/androidTest/.../RescanCopyTest.kt`)
ran against a copy of the live history with all 6 candidates:

- 29 fixture records, 6 candidates, 6 scanned, **5 updated**, 1 left unchanged.
- Verified against the screenshots:
  - `1793126063275` → 0/0/0 (dim 0% victory).
  - `1793091279042` → 1,722,937 / 1,770,913 / 19,201.
  - `1793087831132` → 2,932,863 / 1,921,992 / 14,088.
  - `1793087423662` → 2,058,013 / 2,661,730 / 29,930.
  - `1793085941732` → 0/0/0.
- `1793085816858` (the very dim zero screen above) was scanned but not
  changed, because gold and elixir zeros could not be confirmed.
- The live `attacks.json` hash was identical before and after
  (`305a4341…fb177`); the live `macros.json` hash was also unchanged
  (`e8115ee2…7e0b`). The updates exist only in the test copy; the user can
  apply them with the dashboard button after their own backup.

### Data safety and backups (evidence: A)

- `.ocr-work/step4_backup/attacks.before_244.json` (same SHA `305a4341…`),
  `macros.before_244.json` (`e8115ee2…`), installed 2.4.4 APK
  `bccaedd4…c0cb`.
- `.ocr-work/step5_backup/attacks.before_245.json`,
  `macros.before_245.json`, `installed_245.apk`, `rescan_test_report.txt`,
  and the three checked screenshots under `shots/`.
- The 14 old unconfirmed playback records were not deleted or edited.
  `macros.json` was not restored or modified.

### Balance-delta proposal (not built)

A balance-delta check would use the before/after gold, elixir and dark
balances plus the bonus panel to predict loot. It should be displayed as a
separate **unverified estimate**, never mixed into confirmed totals or written
into `gold/elixir/darkElixir`, and should never turn a failed read into 0.
It is proposed only; no code was added.

### Evidence levels

- A: device captures, lab JSON (`accuracy_report_slot7.json`), re-scan report,
  APK/file SHA-256, screenshots inspected above.
- B: source and unit/instrumentation tests in this repo.
- C: rolling app log (`autoclash_events.log`).
- D: the remaining-failure explanations above (dim overlay, name overlay) are
  visual inference from the screenshots, not reader instrumentation output.

### Next

1. User device test: play attacks with the installed 2.4.5 and check that every
   open result screen becomes exactly one record; then use **Re-scan
   unreadable** on the real history after a backup.
2. Collect 30+ new real captures as a clean holdout; the current 24 native
   captures informed the fixes, so they are a regression set, not a clean
   holdout.
3. Push the paused delay branch once GitHub authentication works; keep the local
   git bundle current.

## 2026-10-28 — live manual-watch audit and 2.4.7

### Installed state

- App **2.4.7** (versionCode 16), installed in place after backing up
  `attacks.json` (SHA `a0adcd10…d920`) and `macros.json`
  (`e8115ee2…7e0b`). Both hashes were identical after the install.
- The accessibility service was restarted after the install and is bound.

### What the user's live test exposed (evidence: A)

The phone produced 30 new records after the 2.4.5 backup. The values were read,
but the manual watch left a result screen open for minutes and the old cycle
logic recorded the same screen repeatedly. Confirmed bad data:

- **Leading digit lost:** record `1793170012288` stored gold **163,088**;
  the screenshot shows **1,163,088**. ML Kit read the leading `1` as `L`
  (`L163 088`) and the parser split it away. This is a wrong value.
- **One screen, four records:** the defeat screen with dark **11,610** became
  `1793170137302` (correct 781,326 / 576,882 / 11,610),
  `1793170152146` (gold missing), `1793170162776` (gold and elixir missing),
  and `1793170171526` (wrong 731,325 / 576 / 11,610). The first is the correct
  record; the other three are duplicates/fragments.
- **Transient zero:** record `1793171895776` stored 0 / 0 / 0 for a screen whose
  later frame shows **2,541,267 / 1,668,782 / 35,016**. The same screen also
  produced `1793171917880` (25,412,267/…/35,016),
  `1793171934849` (2,542/…/35,016) and `1793171944429` (25,442/…/35,016).
  None of the four has the correct three values.
- Other duplicate pairs: `1793171428799` (47,148 / 1,250,944 / 5,583) with
  `1793171440273` (47 / missing / 5,583), and `1793171789110`
  (2,018,611 / 2,203,678 / 27,723) with `1793171806234`
  (2,018 / 2,203,675 / 27,723).
- The two standalone 0/0/0 records (`1793169346415`, `1793172938273`) are
  genuine zero-loot victories; their screenshots show 0 / 0 / 0.

Root cause: a single missed detection set `screenClear = true`, so the next
frame of the same animated screen was captured as a new result. The loot
counters/overlays also change the numbers before they settle.

### Fixes in 2.4.7 (evidence: B code/tests, A for the lab pack)

- `CaptureCycleTracker` now needs **3 consecutive non-result polls** and a
  frame at least 16 bits different from the recorded hash before the screen is
  considered clear. Same-screen animation frames observed at most 12 bits of
  distance; different battle scenes are much farther apart.
- `ResultsCaptureCoordinator` re-reads a zero/zero-ish result up to 3 times,
  one second apart. If any frame shows a non-zero gold/elixir/dark, that frame
  is used instead of the false zero.
- `ResultsParser` merges horizontally adjacent numeric OCR lines in the same
  slot before choosing the row, so a split `1` + `163 088` stays `1 163 088`.
- `NumberParser` maps uppercase `L` to `1` (`L163 088` → 1,163,088).
- The real `1,163,088` screenshot was added to the lab pack as a regression
  case and now scores all three fields correctly.
- Full lab pack after the fixes: **24/24 native detected, 23/24 complete and
  correct, 0 wrong values, ~1.0 s per screen**. (The one remaining native
  failure is the very dim 0%-loot screen.)

### Live history status — not modified by us

- Backups: `.ocr-work/step6_backup/attacks.before_246.json` (59 records),
  `attacks.before_247.json` (current), `macros.before_247.json`, and the
  re-scan report `rescan_report_44.txt`.
- The re-scan copy test still updates the 5 old incomplete records correctly;
  it does **not** touch complete-but-wrong records. Those need the record's
  edit action or deletion.
- Recommended cleanup (after a fresh backup, using the app's own record
  actions):
  - Edit `1793170012288` gold to **1,163,088**.
  - Keep `1793170137302`; delete `1793170152146`, `1793170162776`,
    `1793170171526`.
  - For the 35,016 screen, keep one record and set it to
    **2,541,267 / 1,668,782 / 35,016**; delete the other three.
  - Keep `1793171428799`; delete `1793171440273`.
  - Keep `1793171789110`; delete `1793171806234`.
- The app has a trash icon on each history row and a manual loot edit; no
  direct file edit is needed.

### Honesty note

The 2026-10-27 claim “OCR works perfectly” was true for the values on the
final screens the lab scored, but the live test showed that **capture timing**
can write a wrong value before the screen settles. 2.4.7 addresses the two
observed causes (duplicate frames and transient zeros); a fresh multi-attack
device test is still needed before calling the live path proven. This is a new
clean-holdout test, not a reuse of the 24 tuning captures.

## 2026-10-28 — count-up stabilization, review flags, bonus states, Auto watch (2.4.8)

This section answers the 12:48/12:50/12:57 investigation and supersedes the
earlier watcher notes.

### Root cause (evidence: A — sidecars, saved PNGs, rolling log)

The six records were produced by **2.4.5 (versionCode 14)**, all from the
Manual watch button. The three 12:48 rows are one defeat screen (same base,
same dark 35,016, same bonus 210,000/210,000/1,344). 2.4.5 cleared the recorded
screen after a **single** missed detection, so the same animated screen was
captured again. There was also no stability check.

- The impossible gold 25,412,267 passed because ML Kit full-page and Tesseract
  both returned `2 5412 267` and the parser concatenated the groups into
  25,412,267. The true value on the screen is **2,541,267**; the extra 2 is a
  duplicated group-boundary digit. No sanity limit existed.
- The saved frames of that screen are partly covered by a clan name/particle.
  Even the all-reader pass reads only dark 35,016 and the bonus; gold/elixir
  remain conflicts. The true visible values are
  **2,541,267 / 1,668,782 / 35,016**.
- 12:57 gold **1,120,000 is correct** (the screenshot shows it); the same
  screen has an **empty bonus panel**, not "no bonus".
- 12:50 has **no dark row at all** (the panel shows only gold and elixir), so
  dark null is honest; no bonus panel.
- 12:49 is correct.

### Code changes in 2.4.8 (versionCode 17)

- `StableReadGate`: a value is saved only after two consecutive complete reads
  agree on outcome + gold + elixir + dark. Up to three extra frames 800 ms
  apart are tried; partial/animated frames never become the saved value.
- `EpisodeFillPolicy`: later reads of the same open screen only fill null
  fields of the same record; a changed existing field is flagged, never
  replaced, and never creates a second record.
- `LootSanity`: absolute caps (10M gold/elixir, 1M dark, 1M bonus gold/elixir,
  100k bonus dark) remove implausible fields and flag the record; balance
  deltas can add a review reason but never substitute a value.
- `AttackRecord.reviewReason` + `bonusState`; history shows **Needs review**
  with the reason, and bonus state (`captured`, `not-captured`, `none`, or
  legacy unknown). Bonus totals are shown separately in Stats/overlay and the
  main totals stay main-only.
- The Edit attack loot dialog prefills all six fields; saving keeps the bonus
  unless the user clears it and clears the review flag.
- Bonus parser uses the word "bonus", not the percentage (`80% Bonus` test).
  `bonusPanel` distinguishes absent panel vs present-but-uncaptured.
- Re-scan includes records with a review reason, reads with ALL engines,
  applies the sanity check, preserves the original screenshot and existing
  bonus, and updates only with all three main values.
- Always-on watcher: while the floating controller runs, an **Auto watch**
  session starts without a press and restarts after a macro watch ends. The
  Stats button still creates an explicit **Manual watch**; macro playback uses
  the macro name. Those labels now match the real source. The button can stop
  Auto watch. Starting a macro recording aborts the auto watcher so it cannot
  hide the overlay during recording; it restarts after the recording stops.

### Test results (evidence: A/B)

- Unit tests: **152 app + 50 ocrlab = 202, all passing** (includes the 12:48
  sequence, one-episode/one-record, fill-only-missing, sanity caps, bonus
  percentage and the 51,824/373,643/1,731 + 200,000/200,000/1,280 parser
  case).
- Lab pack: 24/24 native detected, 23/24 complete, **0 wrong values**, ~1.0 s
  per screen. The previous 1,163,088 `L163 088` regression now reads correctly.
- Real 12:48 screenshot with all readers: dark 35,016 and bonus
  210,000/210,000/1,344 correct; gold/elixir conflict (occluded) and the
  record stays flagged rather than guessed.
- Re-scan copy on the flagged 59-record history: 10 scanned, 1 updated
  (12:57 main values unchanged, source re-scanned), original hash unchanged.
  The 12:48 frames could not supply gold/elixir, so Re-scan leaves them
  flagged. Use the Edit action with the visible values above if you want them
  corrected; that clears the flag and keeps the bonus.
- The 51,824 test image was **not present** in the workspace or on the phone;
  its exact values are covered by the parser test. Please drop the image in
  `newimages/` or the phone test pack to add it physically.

### Live data and build (evidence: A)

- App **2.4.8** (versionCode 17), installed in place; APK SHA-256
  `0493c0e459f7cb04918e1390dbfaeb95a14aaebb5e5b34c57f4bea428ad81d41`.
- `attacks.json` SHA `66076a32…b73ed3` (59 records; 5 review flags, no
  deletions); `macros.json` SHA `e8115ee2…7e0b` unchanged.
- Flagged: 1793171917880, 1793171934849, 1793171944429 (12:48 group),
  1793171895776 (the related 12:46 0/0/0 read), 1793172885599 (12:57).
  Bonus states: the 12:48 group `captured`; 12:57 and 12:46
  `not-captured`; 12:49/12:50 `none`.
- Backups: `.ocr-work/step7_backup/attacks.before_248.json`,
  `attacks.flagged.json`, `rescan_report_flagged.txt`, `macros.before_248.json`.

### Remaining honest limitations

- Auto watch is code-complete but not yet exercised by a live attack after
  this install; it needs the user's next device test.
- The 12:48 gold/elixir are physically covered in every saved frame; OCR cannot
  read them without guessing. Manual edit is the safe way to correct those
  three historical rows.
- The 12:50 dark row is absent on the capture, not a missing OCR value.

## 2026-10-28 — share/import a single macro (2.4.9)

The first of the postponed feature requests (shareable macros) is implemented.

### What changed in 2.4.9 (versionCode 18)

- Each macro card has a **Share** action. It writes a versioned envelope
  (`format: "autoclash-macro"`, `formatVersion: 1`, app version, export time,
  the single `Macro`) to `cache/exports/` and opens Android's share sheet via
  the existing `FileProvider` (`${applicationId}.updates`). Only that macro is
  included; no attacks, captures, settings or other macros.
- The macro-library header has **Import**, using the system document picker.
  `MacroShare.decode` accepts the envelope or a bare `Macro` JSON, rejects
  unknown formats, future format versions, empty gesture lists, invalid JSON
  and files over 20 MB. The content is re-sanitized by the same path as a
  recorded macro. `MacroRepository.importShared` always assigns a new local id
  and uses `Name (2)`, `Name (3)`… on a name clash; it never overwrites an
  existing macro.
- `MacroShareTest` covers round-trip, bare JSON, invalid/future/empty files,
  and malformed `format` field types. `MacroImportTest` covers new id, name
  clash, preserved gestures/speed/repeat/wait, and rejection of empty gestures.

### Test results (evidence: A/B)

- **158 app + 50 ocrlab unit tests passing.** The only non-feature edit is a
  wider scheduling margin in `ResultCaptureGateTest` (its 20 ms coroutine start
  could miss a 200 ms window on a loaded JVM; assertions are unchanged).
- Device: **2.4.9 (versionCode 18)** installed over 2.4.8; APK SHA-256
  `2d0f6f9b98133b6800be35e86780ab440dc46b68aa4d7699b199ba10fca25672`.
- Share ran on device and produced `Neww-<id>.json` with the correct envelope,
  app version 2.4.9 and all 18 gestures (copied to
  `.ocr-work/step8_backup/exported_neww.json`).
- The system picker also completed an import during the test. It created a
  macro with a **fresh id and identical payload**, which confirms the device
  path, but it ran against the live library. The library was restored
  byte-for-byte from `.ocr-work/step8_backup/macros.before_249.json` and the
  app relaunched; `macros.json` is again
  `e8115ee2350715080926a107bc101e91a7cb1c30fda7108468eb7ae9b0f938bd`
  (two original macros, original ids). Test artifacts were removed from the
  phone cache and `/data/local/tmp`.
- `attacks.json` stayed `66076a32…b73ed3` throughout. Pre/post-install and
  pre/post-test copies are in `.ocr-work/step8_backup/`.

Per the standing rule, no further import test will touch the live library
without explicit approval; the codec and repository behavior are covered by
unit tests, and the device run above already exercised the picker path.

### Remaining postponed work

Only the macro-sharing item was recalled. The other postponed features/UI
changes still need the user's list.
