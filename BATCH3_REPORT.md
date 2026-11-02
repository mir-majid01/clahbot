# AutoClash 2.10.4 — v6 batch and follow-up report

Date: 2026-11-02  
Release: **2.10.4 (versionCode 30)**  
GitHub release: <https://github.com/mir-majid01/clahbot/releases/tag/v30>  
CI-built APK: `AutoClash-2.10.4.apk` (51,075,717 bytes)  
SHA-256: `3b7cc869c1bb756006dccf783d28e23192ffbfc07f30b50fc7b110f148c35ce9`

The release was built and signed by **GitHub Actions**, not on this machine.
The downloaded release was installed in place on the Infinix X6876; the
installed `/data/app/.../base.apk` hash matches the asset above,
`versionCode=30`, `versionName=2.10.4`, and the package no longer has the
`DEBUGGABLE` flag. App data is preserved by the in-place update.

Automated checks: **234 app unit tests, 2 skipped, 0 failures**; lint
**0 errors**.

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
