# Agent Context — AutoClash Feature 1

Last updated: 2026-10-26

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
  delay to 600 ms. It is not built, installed or verified yet.
- The earlier lab run on the 11-image pack scored 0 wrong / 0 missing, but the
  digit templates voted in that run. That is **not** a valid score under the
  current rule, which excludes templates on their training images.
- Speed on the four result screens is now measured for both strategies; see
  "Verified numbers". The cheap-first strategy is inside the target.
- The 30+ real-capture set has not been collected or scored.

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
| 3. App reader integration + phantom fix | `:ocr` shared library; 2.4.0 (9) and 2.4.1 (10) installed over existing data with file hashes unchanged; 108 app unit tests and CI green. Two real attacks created **no** record and no screenshot, confirming playback completion alone never records, but both missed the capture. The first proved the watcher must run during playback; the second still missed, and the half-scale anchor is the leading suspect. 2.4.2 uses full-resolution detection plus poll logging and is not installed yet. | Install + unit/CI + two real attacks (negative capture) | Automatic capture and record on a real attack with 2.4.2; poll logs; stale-result rejection in the app |
| 4. “Save results screenshots” setting | Setting in `SettingsStore`; Settings UI switch rendered and checked=true on the phone; PNG + JSON + TXT sidecar store; null-not-zero mapping; compile/unit tests. | Setting UI verified on-device; writer unit tested | Actual PNG/sidecar contents from a real attack; 30+ capture collection |
| 5. AGENT_CONTEXT.md | This file | Updated with measured speed, both real-attack findings and the pending 2.4.2 verification | Final on-device evidence for 2.4.2 |

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
  JDK 17 after the watcher-start fix. CI for the 2.4.1 commit is pending.
- Lab benchmark commands used on the phone:
  `adb shell am instrument -w -e strategy all -e subset results -e saveCrops false -e class com.clahbot.ocrlab.OcrLabAccuracyTest com.clahbot.ocrlab.test/androidx.test.runner.AndroidJUnitRunner`
  and the same with `strategy` omitted for cheap-first. Reports pulled to
  `.ocr-work/speed_before.json` and `.ocr-work/speed_after.json`.

## Next actions

1. Build 2.4.2 (versionCode 11) in CI, then ask the user before installing it.
   After approval, run one real attack and verify from the new per-poll logs:
   results screen detected during playback, clean PNG plus JSON/TXT sidecar,
   exactly one confirmed record, values match the screen, missing fields stay
   null, and no record for a stale results screen or playback completion alone.
2. Restore the original `macros.json` from `.ocr-work/macros.before.json` after
   the verification, because selecting the macro and cycling repeat persists
   playback settings into the file (existing app behavior). Confirm its
   SHA-256 matches the pre-install value.
3. Turn on “Save results screenshots” and collect the 30+ real result screens.
   Score them as a fresh (non-training) set, keeping template votes for those
   real captures.
4. Do not start product Features 2-5 without the user’s explicit approval.

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
  phone). This has not been battery- or heat-tested during a long attack. If
  polling is too heavy, the debug setting can be used to collect captures
  manually instead.
- The results screen may be visible for only a few seconds before the macro
  taps Return Home (about 4 s in the first real run). Detection uses a 1 s
  poll; if the clean capture lands after the tap, the retained detection frame
  is read instead. If both fail, no record is written rather than a fake one.
- Changing speed or repeat in the controller persists those playback settings
  into `macros.json` for the selected macro (existing behavior, not new). The
  verification changed the `New` macro's repeat; the original file is restored
  from backup after testing.
