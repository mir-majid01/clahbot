# AutoClash 2.10.0 — v6 batch report

Date: 2026-11-01  
Version: **2.10.0 (versionCode 26)**  
APK: `app/build/outputs/apk/debug/app-debug.apk`  
SHA-256: `dc576cecfb087f63195205da1f9d0f0027b7a0b78cdc1fd90ac8a31e25404655`

Automated checks: **228 app unit tests, 2 skipped, 0 failures**; lint **0 errors**
(146 warnings, all pre-existing hardcoded-text/style warnings). The single APK
was built once with the stable release key.

**Device status:** The APK was installed in place on the Infinix X6876 at
2026-11-01 (versionCode 26 / versionName 2.10.0; installed APK SHA-256 matches
`dc576cecfb087f63195205da1f9d0f0027b7a0b78cdc1fd90ac8a31e25404655`). App data
was preserved (`macros.json` 91,593 bytes, `attacks.json` 14,922 bytes before
and after). The interactive device pass, launcher/splash screenshots and the
20-attack loot run are still pending.

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
