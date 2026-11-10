package com.clahbot.results

import android.content.Context
import android.graphics.BitmapFactory
import com.clahbot.data.AppEventLog
import com.clahbot.data.AttackRepository
import com.clahbot.data.models.AttackRecord
import com.clahbot.ocr.ReaderStrategy
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File

object ResultsRescan {
    data class Summary(val scanned: Int, val updated: Int, val skippedNoFrame: Int, val failed: Int = 0)

    fun candidates(records: List<AttackRecord>): List<AttackRecord> =
        flagged(records).filter { it.resultsScreenshot != null }

    fun flagged(records: List<AttackRecord>): List<AttackRecord> = records.filter {
        it.confirmed && (it.needsReviewDefinition || !it.hasMainLoot)
    }

    suspend fun run(context: Context, repository: AttackRepository): Summary = withContext(Dispatchers.IO) {
        val records = repository.refresh()
        val candidates = candidates(records)
        var skippedNoFrame = flagged(records).size - candidates.size
        if (candidates.isEmpty()) return@withContext Summary(0, 0, skippedNoFrame)

        val reader = AppResultsReader(context)
        var scanned = 0
        var updated = 0
        var failed = 0
        try {
            for (record in candidates) {
                val path = record.resultsScreenshot
                if (path == null || !File(path).isFile) {
                    skippedNoFrame++
                    continue
                }
                val bitmap = runCatching { BitmapFactory.decodeFile(path) }.getOrNull()
                if (bitmap == null) {
                    skippedNoFrame++
                    continue
                }
                scanned++
                try {
                    val result = withTimeoutOrNull(30_000) {
                        reader.read(bitmap, ReaderStrategy.ALL)
                    }
                    if (result == null) {
                        failed++
                        AppEventLog.log("rescan failed id=${record.id} reason=no usable read or timeout")
                        continue
                    }
                    val merged = RescanPolicy.merge(record, result.read, result.rows) ?: continue
                    if (merged == record) continue
                    val changed = repository.update(merged, expected = record)
                    if (changed != null) {
                        updated++
                    } else {
                        failed++
                        AppEventLog.log("rescan skipped id=${record.id} reason=record changed or removed")
                    }
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (error: Exception) {
                    failed++
                    AppEventLog.log("rescan failed id=${record.id} reason=${error.message}")
                } finally {
                    bitmap.recycle()
                }
            }
        } finally {
            reader.closeAsync()
        }
        Summary(scanned, updated, skippedNoFrame, failed)
    }

    fun summaryText(summary: Summary): String {
        if (summary.scanned == 0 && summary.skippedNoFrame == 0 && summary.failed == 0) {
            return "No attacks need review"
        }
        return buildList {
            if (summary.scanned > 0) add("Re-scanned ${summary.scanned}; updated ${summary.updated}")
            if (summary.skippedNoFrame > 0) add("${summary.skippedNoFrame} without a readable saved frame")
            if (summary.failed > 0) add("${summary.failed} failed or changed during re-scan")
        }.joinToString(" · ")
    }
}
