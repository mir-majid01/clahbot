package com.clahbot.walls

import com.clahbot.service.ClahAccessibilityService
import kotlinx.coroutines.CancellationException

/**
 * Runs one read-only scan for the Phase 1 UI: check the game is on screen,
 * capture a frame through the accessibility service, run the real OCR →
 * [WallDiscovery] → [WallSelection] pass, then persist the report.
 *
 * It never taps, scrolls, opens a dialog, evaluates a trigger or starts
 * [AutoWallsRuntime]. The only device call is [WallDevice.observe].
 */
class AutoWallsScanner(
    private val repository: AutoWallsRepository,
    private val device: WallDevice,
    private val now: () -> Long = System::currentTimeMillis,
    private val log: (String) -> Unit = {}
) {

    suspend fun scan(): AutoWallsScanReport {
        val config = repository.loadConfig()
        val foreground = ClahAccessibilityService.currentForegroundPackage()
        val report = try {
            if (!AutoWallsScan.isGameForeground(foreground)) {
                AutoWallsScan.refused(foreground, config, now())
            } else {
                val frame = device.observe()
                if (frame == null) {
                    AutoWallsScan.captureFailed(foreground, config, now())
                } else {
                    AutoWallsScan.analyze(frame, config, now())
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            AutoWallsScan.captureFailed(
                foreground,
                config,
                now(),
                "scan failed: ${e.message ?: e.javaClass.simpleName}"
            )
        }
        repository.saveScan(report)
        log(
            "auto-walls-2.0 scan pkg=${foreground ?: "-"} rows=${report.candidates.size} " +
                "eligible=${report.eligible.size} selected=" +
                (report.selected?.let { "L${it.level}/${it.currency}/${it.price}" } ?: "-") +
                " reason=${report.reason ?: "ok"} tapping=${report.allowTapping}"
        )
        return report
    }
}
