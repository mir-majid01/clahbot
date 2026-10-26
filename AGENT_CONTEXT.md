# Agent Context — AutoClash Feature 1

Last updated: 2026-10-26

Scope: Feature 1 results-screen loot OCR and its integration into AutoClash.
Ranked is untouched. Product Features 2-5 have not been started.

## Current status

- The shared OCR core lives in the `:ocr` library; both the OCR lab and the
  AutoClash app consume it.
- The app is version 2.4.0 (versionCode 9), arm64-only for the test phone.
- The app integration compiles and its unit tests pass. It is **not installed
  on the phone** yet, so capture, automatic attack records, screenshots, and
  the phantom-record guards have **not** been verified on a real attack.
- The earlier lab run on the 11-image pack scored 0 wrong / 0 missing, but the
  digit templates voted in that run. That is **not** a valid score under the
  current rule, which excludes templates on their training images.
- The all-readers timing baseline was run with templates excluded from
  consensus/scoring, but the phone disconnected before the report was pulled.
  The cheap-first after measurement is therefore still pending.
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

## App integration

- Accessibility is used for capture: `AccessibilityService.takeScreenshot`
  (Android 11+), with `android:canTakeScreenshot="true"` in the service config.
  Shizuku remains the input/getevent transport; it is not used for capture.
- Playback start captures a baseline screenshot and checks it for result-screen
  anchors. If the results screen was already open before playback, it is treated
  as stale and no record is created.
- Playback end starts a bounded results watcher (4 min) because the battle can
  outlast the macro playback. The bubble stays usable while polling. Detection
  uses a half-scale screenshot; once the result anchors appear, the bubble is
  hidden for a clean full-resolution capture, then restored. This avoids hiding
  the controller for a whole battle.
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
- The current app APK is arm64-only (57 MB debug) because the OCR native
  libraries made the universal APK about 151 MB. The lab APK is also arm64-only
  (50 MB). This is a deliberate limitation of this build.

## Evidence ledger

| Item | Evidence | Level | Still unverified |
|---|---|---|---|
| 1. Template provenance + exclusion | Exact source images recorded above. Template classification is excluded from votes/scores on 08-11 in code. The earlier 0/0 run included templates, so it is not counted. | Code + source evidence; current-rule device rerun pending | Current-rule accuracy on 08-11 after exclusion |
| 2. Speed before/after | Initial v0.4 lab run took 578 s for 11 images with every reader and template votes (not a per-reader table). Timing instrumentation and cheap-first logic are implemented. An all-readers timing run completed on the phone, but adb disconnected before the report could be pulled; the cheap-first after run is pending. | Partial: only the 578 s total is measured; no per-screen before/after numbers claimed | Per-reader/per-screen before and after; target “a few seconds per screen” |
| 3. App reader integration + phantom fix | `:ocr` shared library; app v2.4.0 (9) compiles; 108 app unit tests pass; APK builds; CI `40368076334` green for the shared-library integration. Results-to-record mapping and capture policy are unit tested. No playback-completion record path exists. | Build + unit tests + CI; on-device unverified | Accessibility screenshot success, auto record on a real attack, stale-result rejection, no phantom record on a real menu/home screen |
| 4. “Save results screenshots” setting | Setting in `SettingsStore`; Settings UI switch; PNG + JSON + TXT sidecar store; null-not-zero mapping; compile/unit tests. | Build + unit tests; UI/on-device unverified | Switch rendering, file paths, sidecar contents, 30+ capture collection |
| 5. AGENT_CONTEXT.md | This file | Written | It must be updated with the pending speed numbers and the on-device results |

## Verified numbers

- Initial lab v0.4 on the phone, all readers including templates:
  11 images, 4/4 result screens, 11/11 classification, 0 false positives,
  0 wrong captures, 0 missing. **Templates were part of consensus, so this does
  not validate the current scoring rule.**
- Initial per-engine accuracy from that run (also template-contaminated):
  ML Kit full 37/37, ML Kit crop 33/33, Tesseract 25/33 (4 wrong, 4 missing),
  RapidOCR 32/33 (1 wrong), templates 33/33, consensus 37/37.
  The Tesseract/Rapid errors were outvoted by consensus.
- The 10/09 balance identity passed (`balanceCheck=pass` for screen 10).
- Total wall time of that run: 578 s for 11 images (includes startup, model
  loading, full-page OCR of non-result frames, all readers, and crop saving).

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
  uploads. The later watcher/timing-only app commit still needs its CI run.

## Next actions

1. Reconnect the phone (USB adb is currently disconnected). Pull the completed
   all-readers timing report, then install the current lab build and run the
   cheap-first timing pass to fill the before/after table.
2. Ask the user before installing app v2.4.0. After approval, run one real
   attack and verify: accessibility screenshot, automatic confirmed record,
   sidecar values, null-not-zero, and no record on a stale results screen.
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
- The results watcher runs for up to 4 minutes after playback. Detection OCR
  runs periodically at half resolution while the battle can still be running;
  this has not yet been battery-tested on the phone.
