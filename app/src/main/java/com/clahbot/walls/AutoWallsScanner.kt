package com.clahbot.walls

import com.clahbot.data.AppEventLog
import com.clahbot.gesture.ScanGate
import com.clahbot.service.ClahAccessibilityService
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import java.util.Locale
import java.util.concurrent.atomic.AtomicLong

/**
 * Runs one read-only scan for the Phase 1 UI: check the game is on screen,
 * capture a frame, verify the `Buildings & Heroes` list is actually open, then
 * read the list viewport by viewport from top to bottom.
 *
 * Scrolling is deliberately controlled:
 *  - the first frame must show the list header, otherwise the scan refuses and
 *    never scrolls blindly;
 *  - the header is re-verified on every viewport, so a popup (Goblin offer) or
 *    a screen change stops the scan instead of reading the wrong content;
 *  - each viewport is read independently, so a row's price can only come from
 *    that same row;
 *  - overlapping rows are merged by wall-group identity, not by screen
 *    position;
 *  - a safety abort fires if the header disappears mid-scan or a building
 *    info/selection screen appears — this prevents gestures after a popup;
 *  - "bottom" is only declared when the list provably stopped moving (equal
 *    list signatures across a scroll attempt) or when two viewports in a row
 *    added nothing to a list that did move;
 *  - a couple of wall-free viewports are tolerated (the list starts with
 *    suggested upgrades), but a longer run of empty captures stops the scan
 *    instead of being mistaken for a long list.
 *
 * While the scan runs it holds [ScanGate], so no other automation may inject
 * competing gestures. When the loop ends — normally, on a failed scroll, on a
 * popup, or on cancellation — the list is scrolled back to the position the
 * scan started from, and the recovery is logged.
 *
 * It never taps, opens a dialog, evaluates a trigger or starts
 * [AutoWallsRuntime]. The only device calls are [WallDevice.observe], the list
 * scroll and the restore scroll. Persisted reports keep `allowTapping` false
 * on reload.
 */
class AutoWallsScanner(
    private val repository: AutoWallsRepository,
    private val device: WallDevice,
    private val now: () -> Long = System::currentTimeMillis,
    private val foreground: () -> String? = { ClahAccessibilityService.currentForegroundPackage() },
    private val log: (String) -> Unit = {}
) {

    suspend fun scan(): AutoWallsScanReport {
        val config = repository.loadConfig()
        val foregroundPackage = foreground()
        // Only the scanner scrolls while a scan runs; other automation holds
        // its gestures until the gate is released in finally.
        val scanId = SCAN_IDS.incrementAndGet()
        val gated = ScanGate.begin(scanId)
        if (!gated) {
            log("auto-walls-2.0 scan gate unavailable: another scan holds the screen")
        }
        // The device uses this id as the gesture session id, so the shared
        // authority recognises the scan's own swipe while the gate is held and
        // refuses every other automation surface.
        device.scanOwnerId = scanId
        val report = try {
            if (!AutoWallsScan.isGameForeground(foregroundPackage)) {
                AutoWallsScan.refused(foregroundPackage, config, now())
            } else {
                val first = device.observe()
                if (first == null) {
                    AutoWallsScan.captureFailed(foregroundPackage, config, now())
                } else {
                    // The header read is a single OCR pass and can miss on a
                    // good frame (device recording: list open, header plainly
                    // visible, one scan refused and the next succeeded). Take
                    // a couple more read-only captures — never scrolling —
                    // before refusing.
                    var frame: WallFrame = first
                    var menu = WallMenu.inspect(frame.lines, frame.height)
                    var recaptures = 0
                    // Log the exact top-band text of every attempt: a refusal
                    // must name the check that failed and the raw values it
                    // saw, not just "not detected".
                    log(
                        "auto-walls-2.0 header attempt=0 detected=${menu.scanEnabled} " +
                            "band=${menu.headerDetail}"
                    )
                    while (!menu.scanEnabled && recaptures < HEADER_RECAPTURES) {
                        device.wait(HEADER_RECAPTURE_WAIT_MS)
                        val again = device.observe()
                        if (again == null) {
                            log("auto-walls-2.0 header attempt=${recaptures + 1} capture failed")
                            break
                        }
                        frame = again
                        menu = WallMenu.inspect(frame.lines, frame.height)
                        recaptures++
                        log(
                            "auto-walls-2.0 header attempt=$recaptures " +
                                "detected=${menu.scanEnabled} band=${menu.headerDetail}"
                        )
                    }
                    if (!menu.scanEnabled) {
                        log("auto-walls-2.0 header refused: ${menu.headerDetail}")
                        AutoWallsScan.listNotDetected(
                            foregroundPackage,
                            config,
                            now(),
                            menu.reason ?: "wall list not detected",
                            frame = frame
                        )
                    } else {
                        scanList(frame, config)
                    }
                }
            }
        } catch (e: CancellationException) {
            // Any viewport already scrolled is restored by scanList's finally.
            throw e
        } catch (e: Exception) {
            AutoWallsScan.captureFailed(
                foregroundPackage,
                config,
                now(),
                "scan failed: ${e.message ?: e.javaClass.simpleName}"
            )
        } finally {
            if (gated) ScanGate.end(scanId)
            device.scanOwnerId = 0L
        }
        repository.saveScan(report)
        log(
            "auto-walls-2.0 scan pkg=${foregroundPackage ?: "-"} rows=${report.candidates.size} " +
                "eligible=${report.eligible.size} selected=" +
                (report.selected?.let { "L${it.level}/${it.currency}/${it.price}" } ?: "-") +
                " viewports=${report.viewports} bottom=${report.bottomReached} " +
                "builders=${report.builderStatus} " +
                "reason=${report.reason ?: report.scanNote ?: "ok"} tapping=${report.allowTapping}"
        )
        return report
    }

    /**
     * Controlled top-to-bottom read of the open list. The first frame is
     * already captured; each iteration reads it, then scrolls one controlled
     * amount and captures again. When the loop ends for any reason the list is
     * scrolled back up (cancellation included), so the user finds the list
     * where they left it.
     *
     * Safety: after every capture the frame is checked for the Buildings &
     * Heroes header. If any screen change (popup, building info panel, a tap
     * on a row or village) caused the header to disappear, the scan stops
     * immediately and never issues another gesture.
     */
    private suspend fun scanList(
        first: WallFrame,
        config: AutoWallsConfig
    ): AutoWallsScanReport {
        var rows = emptyList<WallCandidate>()
        var counters = readBalances(first, viewport = 1)
        var builder = readBuilders(first, viewport = 1)
        var frame = first
        var viewports = 1
        var bottom = false
        var note: String? = null
        var emptyViewports = 0
        var scrolled = 0
        // Consecutive scrolls that left the list signature identical. Two of them
        // in a row is the only trustworthy "the list is at its bottom" signal:
        // "no new rows appeared" is not, because a wall-free viewport adds
        // nothing while the list keeps moving, and that mistake is why a scan
        // whose first viewport held no walls refused to scroll at all.
        var unchangedScrolls = 0
        // Pre-scan list signature for restore verification.
        val preScanSignature = WallMenu.listSignature(first.lines, first.height)

        // Log every candidate row with per-field diagnostics.
        logCandidates("viewport=1", first)

        try {
            while (true) {
                // SAFETY: verify the list is still on screen before every capture.
                val menu = WallMenu.inspect(frame.lines, frame.height)
                if (!menu.scanEnabled) {
                    note = "wall list no longer on screen; stopped at viewport $viewports " +
                        "(safety abort: header disappeared)"
                    break
                }
                if (frame.observation.state != first.observation.state) {
                    note = "screen changed mid-scan (${frame.observation.state.name}); " +
                        "stopped at viewport $viewports (safety abort)"
                    break
                }

                val found = frame.candidates()
                if (found.isEmpty()) {
                    emptyViewports++
                    val warning = if (emptyViewports > WallMenu.EMPTY_VIEWPORT_WARNING) {
                        " unreadable-streak"
                    } else {
                        ""
                    }
                    log(
                        "auto-walls-2.0 viewport=$viewports empty (streak=$emptyViewports)" +
                            "$warning unchangedScrolls=$unchangedScrolls scrolled=$scrolled"
                    )
                } else {
                    emptyViewports = 0
                }

                val previousSignature = WallMenu.listSignature(frame.lines, frame.height)
                val merge = WallMenu.merge(rows, found)
                rows = merge.rows

                if (viewports >= WallMenu.MAX_VIEWPORTS) {
                    note = "stopped after ${WallMenu.MAX_VIEWPORTS} viewports without reaching the bottom"
                    break
                }
                if (!device.scroll()) {
                    note = "could not scroll the list; scanned $viewports viewport(s)"
                    break
                }
                scrolled++
                device.wait(SCROLL_SETTLE_MS)
                val next = device.observe() ?: run {
                    note = "screen capture failed after scrolling"
                    null
                } ?: break
                val nextSignature = WallMenu.listSignature(next.lines, next.height)
                val moved = !WallMenu.framesUnchanged(previousSignature, nextSignature)
                if (moved) {
                    unchangedScrolls = 0
                } else {
                    unchangedScrolls++
                    log(
                        "auto-walls-2.0 scroll=$scrolled did not move the list " +
                            "(unchangedScrolls=$unchangedScrolls) before=[$previousSignature] " +
                            "after=[$nextSignature]"
                    )
                }
                frame = next
                viewports++
                // Log candidates on every viewport.
                logCandidates("viewport=$viewports after scroll=$scrolled", frame)
                // Carry the last valid balance forward only with a stale marker.
                counters = next.counters(counters)
                // The builder strip does not move with the list, so the extra
                // re-captures in readBuilders happened at the first viewport.
                // Later viewports only contribute a single sample.
                val nextBuilder = BuilderDetection.detect(
                    next.lines, next.width, next.height, next.builderFaceAt
                )
                if (nextBuilder.confidence > builder.confidence) builder = nextBuilder
                // Two scrolls that changed nothing mean the list is at its true
                // bottom; stop without spending another capture.
                if (unchangedScrolls >= BOTTOM_UNCHANGED_SCROLLS) {
                    bottom = true
                    break
                }
            }
        } finally {
            // Restore the list position. The swipe itself runs inside
            // NonCancellable so a cancelled scan still puts the list back where
            // it was; nothing else in the restore path may block the caller.
            val restored = if (scrolled > 0) {
                withContext(NonCancellable) { device.scrollBack(scrolled) }
            } else {
                0
            }
            log("auto-walls-2.0 scan restore scrolled=$scrolled restored=$restored")
            // The signature verification is best-effort: it runs OUTSIDE
            // NonCancellable so a cancelled scan never waits for the list to
            // settle and the JVM tests are never stuck in a non-cancellable
            // wait.
            try {
                verifyRestore(scrolled, preScanSignature)
            } catch (e: CancellationException) {
                log("auto-walls-2.0 restore verify skipped (scan cancelled)")
                throw e
            }
        }

        if (rows.isEmpty() && note == null) {
            note = "no wall rows readable in $viewports viewport(s); " +
                if (bottom) {
                    "the list was scrolled to its bottom, so it likely holds no walls"
                } else {
                    "the rows could not be read before the viewport limit"
                }
        }

        return AutoWallsScan.analyzeScan(
            rows = rows,
            frame = first,
            config = config,
            counters = counters,
            builder = builder,
            viewports = viewports,
            bottomReached = bottom,
            scanNote = note,
            at = now()
        )
    }

    /**
     * Reads builder availability from the first viewport, capturing again when
     * the first frame is not decisive.
     *
     * The badge or the icon can be hidden by a banner or a floating panel on any
     * single frame, so up to [BUILDER_RECAPTURES] fresh captures are taken (no
     * scroll: the strip does not move) and the readings are folded with
     * [BuilderDetection.majority]. A lone decisive reading is not a majority, so
     * an ambiguous strip still ends as "uncertain" instead of a guessed count.
     */
    private suspend fun readBuilders(
        first: WallFrame,
        viewport: Int
    ): BuilderDetection.Reading {
        val readings = mutableListOf(
            BuilderDetection.detect(first.lines, first.width, first.height, first.builderFaceAt)
        )
        logBuilders(viewport, attempt = 1, readings.last(), first)
        var attempt = 1
        while (!readings.last().isDecisive && attempt <= BUILDER_RECAPTURES) {
            device.wait(BUILDER_RECAPTURE_WAIT_MS)
            val frame = device.observeAgain()
            if (frame == null) {
                log("auto-walls-2.0 builders attempt=${attempt + 1} capture failed")
                break
            }
            attempt++
            readings += BuilderDetection.detect(
                frame.lines, frame.width, frame.height, frame.builderFaceAt
            )
            logBuilders(viewport, attempt, readings.last(), frame)
        }
        val merged = BuilderDetection.majority(readings)
        log(
            "auto-walls-2.0 builders decision viewport=$viewport samples=${readings.size} " +
                "status=${merged.status} free=${merged.freeBuilders ?: "-"} " +
                "total=${merged.totalBuilders ?: "-"} " +
                "busy=${merged.busyBuilders ?: "-"} " +
                "goblin=${merged.goblinBuilderOffer}/${merged.goblinOfferKnown} " +
                "conf=${"%.2f".format(Locale.US, merged.confidence)} line=\"${merged.panelLine()}\""
        )
        return merged
    }

    /**
     * Reads the top resource counters from the first viewport, taking up to
     * [BALANCE_SAMPLES] fresh captures of the same unchanged screen.
     *
     * The HUD does not move during a scan (no upgrade happens), so a value that
     * only one capture saw is exactly the kind of mis-read a passing banner or
     * an animation causes. [WallCounters.agree] accepts a currency only when two
     * samples agree, otherwise it stays unread/uncertain. Each sample is logged
     * with its OCR timing so a slow scan can be explained from the log.
     */
    private suspend fun readBalances(
        first: WallFrame,
        viewport: Int
    ): WallCounters.Reading {
        val startedAt = now()
        val readings = mutableListOf(first.counters())
        logBalances(viewport, attempt = 1, readings.last())
        var attempt = 1
        while (attempt < BALANCE_SAMPLES) {
            // No settle wait here: a re-capture is a fresh frame of a screen that
            // has not moved (no scroll, no upgrade), and the OCR pass itself
            // gives the HUD time to repaint.
            val frame = device.observeAgain()
            if (frame == null) {
                log("auto-walls-2.0 balances attempt=${attempt + 1} capture failed")
                break
            }
            attempt++
            readings += frame.counters()
            logBalances(viewport, attempt, readings.last())
        }
        val merged = WallCounters.agree(readings)
        log(
            "auto-walls-2.0 balances decision viewport=$viewport samples=${readings.size} " +
                "gold=${merged.gold ?: "-"} goldStale=${merged.goldStale} " +
                "elixir=${merged.elixir ?: "-"} elixirStale=${merged.elixirStale} " +
                "ocrMs=${now() - startedAt} line=\"${merged.describe()}\""
        )
        return merged
    }

    private fun logBalances(viewport: Int, attempt: Int, reading: WallCounters.Reading) {
        log(
            "auto-walls-2.0 balances viewport=$viewport sample=$attempt " +
                "gold=${reading.gold ?: "-"} elixir=${reading.elixir ?: "-"} " +
                "rows=${reading.rows} classified=${reading.classified} " +
                "line=\"${reading.describe()}\""
        )
    }

    private fun logBuilders(
        viewport: Int,
        attempt: Int,
        reading: BuilderDetection.Reading,
        frame: WallFrame
    ) {
        val detail = runCatching { frame.builderFaceDetail() }.getOrDefault("")
        log(
            "auto-walls-2.0 builders viewport=$viewport attempt=$attempt " +
                "status=${reading.status} free=${reading.freeBuilders ?: "-"} " +
                "total=${reading.totalBuilders ?: "-"} face=${reading.face ?: "none"} " +
                "conf=${"%.2f".format(Locale.US, reading.confidence)} " +
                "faceDetail=\"$detail\""
        )
    }

    /**
     * Logs the per-field diagnostic for every discovered wall candidate.
     * This reveals whether a row is PARTIAL or UNCERTAIN because the level was
     * missing, the price was unreadable, the currency was unknown, or the
     * title did not match.
     */
    private fun logCandidates(label: String, frame: WallFrame) {
        if (frame.lines.isEmpty()) return
        val candidates = WallDiscovery.discover(frame.lines, frame.height, currencyAt = frame.currencyAt)
        val headerOk = WallMenu.inspect(frame.lines, frame.height).scanEnabled
        log("auto-walls-2.0 $label header=$headerOk raw=${frame.lines.size} candidates=${candidates.size}")
        for (c in candidates) {
            val missing = buildList {
                if (c.level <= 0) add("level=$levelUnknown")
                if (c.price <= 0L) add("price=$priceUnknown")
                if (c.currency == "unknown" || c.currency.isBlank()) add("currency=$currencyUnknown")
                if (c.confidence < 0.6f) add("confidence=${String.format(Locale.US, "%.2f", c.confidence)}")
            }
            log(
                "auto-walls-2.0   candidate id=${c.id} title=\"${c.title ?: "-"}\" " +
                    "level=${c.level} price=${c.price} currency=${c.currency} " +
                    "count=${c.count ?: "-"} conf=${"%.2f".format(c.confidence)} " +
                    "state=${c.state} ${if (missing.isEmpty()) "ok" else "missing:${missing.joinToString(",")}"}"
            )
        }
    }

    /**
     * Verifies that the list really is back at the position the scan started
     * from, and corrects it when it is not.
     *
     * The swipe count is NOT proof: a completed gesture does not mean the list
     * moved. So the pre-scan signature (header + first rows) is compared with
     * the signature of a fresh capture after the restore, and up to
     * [RESTORE_RETRIES] corrective upward swipes are attempted until the
     * signatures match. Every step is logged.
     */
    private suspend fun verifyRestore(scrolled: Int, preScanSignature: List<String>) {
        if (scrolled <= 0) return
        // Verify the list returned to the top: capture a fresh frame and
        // compare the pre-scan and post-restore signatures.
        device.wait(SCROLL_SETTLE_MS)
        val fresh = device.observe()
        if (fresh == null) {
            log("auto-walls-2.0 restore verify capture failed — position unknown")
            return
        }
        val postSignature = WallMenu.listSignature(fresh.lines, fresh.height)
        // Limit the comparison to the first few rows; long lists differ.
        val targetRows = preScanSignature.take(3)
        val actualRows = postSignature.take(3)
        val match = targetRows == actualRows
        if (match) {
            log("auto-walls-2.0 restore verify signature matches top $targetRows")
            return
        }
        log("auto-walls-2.0 restore verify MISMATCH pre=$targetRows post=$actualRows — applying corrective swipes")
        // Apply up to RESTORE_RETRIES corrective up-swipes.
        var tries = 0
        var currentFrame: WallFrame = fresh
        while (tries < RESTORE_RETRIES) {
            tries++
            val ok = device.scrollUp()
            if (!ok) {
                log("auto-walls-2.0 restore corrective swipe $tries failed")
                return
            }
            device.wait(SCROLL_SETTLE_MS)
            currentFrame = device.observe() ?: return
            val checkSig = WallMenu.listSignature(currentFrame.lines, currentFrame.height)
            if (checkSig.take(3) == targetRows) {
                log("auto-walls-2.0 restore corrective swipe $tries succeeded")
                return
            }
            log("auto-walls-2.0 restore corrective swipe $tries still mismatched")
        }
        log("auto-walls-2.0 restore corrective swipes exhausted — position may be wrong")
    }

    companion object {
        /** Pause for the list to settle after a scroll before reading again. */
        const val SCROLL_SETTLE_MS = 800L
        /** Extra read-only captures before declaring the header missing. */
        const val HEADER_RECAPTURES = 2
        /** Pause between header re-captures, enough for one OCR pass to differ. */
        const val HEADER_RECAPTURE_WAIT_MS = 500L
        /** Max retries for corrective restore swipes. */
        private const val RESTORE_RETRIES = 3
        /** Extra read-only captures when the builder strip is not decisive. */
        private const val BUILDER_RECAPTURES = 2
        /** Pause between builder re-captures, long enough for one OCR pass. */
        private const val BUILDER_RECAPTURE_WAIT_MS = 400L
        /** Captures of the unchanged HUD used for the 2-of-3 balance agreement. */
        private const val BALANCE_SAMPLES = 3
        /**
         * Consecutive scrolls with an unchanged list signature before the scan
         * declares the real bottom. Two is the minimum that proves the list is
         * not merely late to load.
         */
        private const val BOTTOM_UNCHANGED_SCROLLS = 2

        private val SCAN_IDS = AtomicLong()
    }
}

/** Sentinel used in candidate diagnostics for missing values. */
private const val levelUnknown = -1
private const val priceUnknown = -1L
private const val currencyUnknown = ""
private const val STATE_IDENTIFIED = ""