package com.clahbot.results

import android.content.Context
import android.graphics.Bitmap
import android.util.Log
import com.clahbot.ocr.ResultsRead
import com.clahbot.ocr.RowReading
import com.google.gson.GsonBuilder
import java.io.File
import java.io.FileOutputStream
import java.util.Locale

data class SavedCapture(
    val png: String?,
    val json: String?,
    val txt: String?
)

/**
 * Saves a detected result screen and the app's own read next to it. Missing
 * values stay null and are written as "loot not captured"; zero is only ever
 * written when the game actually showed zero.
 */
class ResultsScreenshotStore(private val context: Context) {

    private data class Sidecar(
        val capturedAt: Long,
        val attackId: Long,
        val macroName: String,
        val outcome: String,
        val lootStatus: String,
        val gold: Long?,
        val elixir: Long?,
        val darkElixir: Long?,
        val bonusGold: Long?,
        val bonusElixir: Long?,
        val bonusDarkElixir: Long?,
        val balanceGold: Long?,
        val balanceElixir: Long?,
        val balanceDarkElixir: Long?,
        val confidence: Double,
        val needsReview: Boolean,
        val issues: List<String>,
        val source: String,
        val timingsMs: Map<String, Long>,
        val read: ResultsRead?,
        val evidence: List<RowReading>
    )

    fun save(
        bitmap: Bitmap,
        session: CaptureSession,
        result: AppResultsReader.ReadResult
    ): SavedCapture {
        val directory = context.getExternalFilesDir("results_captures")
            ?: File(context.filesDir, "results_captures").apply { mkdirs() }
        directory.mkdirs()
        val read = result.read
        val outcome = read.outcome.name.lowercase(Locale.US)
        val capturedAt = System.currentTimeMillis()
        val lootStatus = when {
            read.gold == null && read.elixir == null && read.darkElixir == null ->
                "loot not captured"

            read.gold != null && read.elixir != null && read.darkElixir != null ->
                "loot captured"

            else -> "partial loot; missing values are not captured"
        }
        val base = buildString {
            append(capturedAt)
            append('_').append(outcome)
            append("_G").append(read.gold?.toString() ?: "x")
            append("_E").append(read.elixir?.toString() ?: "x")
            append("_D").append(read.darkElixir?.toString() ?: "x")
        }
        val png = File(directory, "$base.png")
        val json = File(directory, "$base.json")
        val txt = File(directory, "$base.txt")
        val sidecar = Sidecar(
            capturedAt = capturedAt,
            attackId = session.id,
            macroName = session.macroName,
            outcome = outcome,
            lootStatus = lootStatus,
            gold = read.gold,
            elixir = read.elixir,
            darkElixir = read.darkElixir,
            bonusGold = read.bonusGold,
            bonusElixir = read.bonusElixir,
            bonusDarkElixir = read.bonusDarkElixir,
            balanceGold = read.balanceGold,
            balanceElixir = read.balanceElixir,
            balanceDarkElixir = read.balanceDarkElixir,
            confidence = read.confidence,
            needsReview = read.needsReview,
            issues = read.issues,
            source = "accessibility-screenshot",
            timingsMs = result.timingsMs,
            read = read,
            evidence = result.rows
        )
        return SavedCapture(
            png = writePng(png, bitmap),
            json = writeText(
                json,
                GsonBuilder().setPrettyPrinting().create().toJson(sidecar)
            ),
            txt = writeText(txt, textSummary(session, sidecar))
        ).also { cleanup(directory, base) }
    }

    /**
     * Saves the one screenshot for a detected screen whose loot could not be
     * read. The sidecar is deliberately value-free so a later re-scan can
     * update the same record without inventing loot.
     */
    fun saveUnreadable(
        bitmap: Bitmap,
        session: CaptureSession,
        reason: String
    ): SavedCapture {
        val directory = context.getExternalFilesDir("results_captures")
            ?: File(context.filesDir, "results_captures").apply { mkdirs() }
        directory.mkdirs()
        val capturedAt = System.currentTimeMillis()
        val base = "${capturedAt}_unreadable_Gx_Ex_Dx"
        val png = File(directory, "$base.png")
        val json = File(directory, "$base.json")
        val txt = File(directory, "$base.txt")
        val sidecar = Sidecar(
            capturedAt = capturedAt,
            attackId = session.id,
            macroName = session.macroName,
            outcome = "unknown",
            lootStatus = "loot not captured",
            gold = null,
            elixir = null,
            darkElixir = null,
            bonusGold = null,
            bonusElixir = null,
            bonusDarkElixir = null,
            balanceGold = null,
            balanceElixir = null,
            balanceDarkElixir = null,
            confidence = 0.0,
            needsReview = true,
            issues = listOf(reason),
            source = "accessibility-screenshot",
            timingsMs = emptyMap(),
            read = null,
            evidence = emptyList()
        )
        return SavedCapture(
            png = writePng(png, bitmap),
            json = writeText(
                json,
                GsonBuilder().setPrettyPrinting().create().toJson(sidecar)
            ),
            txt = writeText(txt, textSummary(session, sidecar))
        ).also { cleanup(directory, base) }
    }

    private fun cleanup(directory: File, keepBase: String) {
        val files = directory.listFiles()?.filter { it.isFile }.orEmpty()
        val entries = files.groupBy { it.nameWithoutExtension }.map { (base, group) ->
            CaptureRetention.Entry(
                base = base,
                files = group.map { it.name },
                bytes = group.sumOf { it.length() },
                modified = group.maxOf { it.lastModified() }
            )
        }
        val delete = CaptureRetention.selectForDeletion(
            entries = entries,
            maxCaptures = MAX_CAPTURES,
            maxBytes = MAX_BYTES,
            keepBase = keepBase
        )
        if (delete.isEmpty()) return
        var removed = 0
        for (entry in entries.filter { it.base in delete }) {
            for (name in entry.files) {
                if (File(directory, name).delete()) removed++
            }
        }
        Log.i(TAG, "Capture retention removed $removed old files (${delete.size} captures)")
    }

    private fun textSummary(session: CaptureSession, sidecar: Sidecar): String = buildString {
        appendLine("AutoClash result capture")
        appendLine("Macro: ${session.macroName}")
        appendLine("Outcome: ${sidecar.outcome}")
        appendLine("Gold: ${valueText(sidecar.gold)}")
        appendLine("Elixir: ${valueText(sidecar.elixir)}")
        appendLine("Dark elixir: ${valueText(sidecar.darkElixir)}")
        appendLine("Bonus gold: ${valueText(sidecar.bonusGold)}")
        appendLine("Bonus elixir: ${valueText(sidecar.bonusElixir)}")
        appendLine("Bonus dark: ${valueText(sidecar.bonusDarkElixir)}")
        appendLine("Balance gold: ${valueText(sidecar.balanceGold)}")
        appendLine("Balance elixir: ${valueText(sidecar.balanceElixir)}")
        appendLine("Balance dark: ${valueText(sidecar.balanceDarkElixir)}")
        appendLine("Status: ${sidecar.lootStatus}")
        appendLine("Confidence: ${"%.2f".format(Locale.US, sidecar.confidence)}")
        appendLine("Needs review: ${sidecar.needsReview}")
        if (sidecar.issues.isNotEmpty()) {
            appendLine("Issues: ${sidecar.issues.joinToString("; ")}")
        }
    }

    private fun valueText(value: Long?): String =
        value?.toString() ?: "loot not captured"

    private fun writePng(file: File, bitmap: Bitmap): String? {
        return try {
            FileOutputStream(file).use { output ->
                if (!bitmap.compress(Bitmap.CompressFormat.PNG, 100, output)) {
                    return null
                }
            }
            file.absolutePath
        } catch (t: Throwable) {
            Log.w(TAG, "Could not save result screenshot", t)
            null
        }
    }

    private fun writeText(file: File, text: String): String? {
        return try {
            file.writeText(text)
            file.absolutePath
        } catch (t: Throwable) {
            Log.w(TAG, "Could not save result sidecar", t)
            null
        }
    }

    companion object {
        private const val TAG = "AutoClashResults"
        private const val MAX_CAPTURES = 200
        private const val MAX_BYTES = 800L * 1024L * 1024L
    }
}
