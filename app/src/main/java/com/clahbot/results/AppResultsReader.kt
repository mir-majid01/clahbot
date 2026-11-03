package com.clahbot.results

import android.content.Context
import android.graphics.Bitmap
import com.clahbot.ocr.BitmapIconColorSampler
import com.clahbot.ocr.DigitTemplateEngine
import com.clahbot.ocr.DigitTemplateStore
import com.clahbot.ocr.Inspection
import com.clahbot.ocr.MlKitOcrEngine
import com.clahbot.ocr.OcrPage
import com.clahbot.ocr.RapidOcrRowEngine
import com.clahbot.ocr.ReaderStrategy
import com.clahbot.ocr.ResultsAssembler
import com.clahbot.ocr.ResultsRead
import com.clahbot.ocr.ResultsScreenParser
import com.clahbot.ocr.RowCropReader
import com.clahbot.ocr.RowReading
import com.clahbot.ocr.TesseractRowEngine
import com.clahbot.ocr.TimingKeys
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * App-side results-screen reader. It warms the expensive engines while the
 * attack is still running, then reads one screenshot through the same
 * multi-reader core as the OCR lab. No balance history is assumed here.
 */
class AppResultsReader(private val context: Context) {

    data class ReadResult(
        val inspection: Inspection,
        val page: OcrPage,
        val rows: List<RowReading>,
        val read: ResultsRead,
        val timingsMs: Map<String, Long>
    )

    /**
     * An episode frame keeps the page even when the anchors are missing, so the
     * caller can tell "this is not the result screen" from "the result screen
     * is still there but the rows did not read".
     */
    data class EpisodeRead(
        val page: OcrPage,
        val inspection: Inspection?,
        val read: ResultsRead?,
        val rows: List<RowReading>,
        val timingsMs: Map<String, Long>
    )

    data class ScreenCheck(
        val isResults: Boolean,
        val reason: String?,
        val page: OcrPage? = null,
        val inspection: Inspection? = null
    )

    private val mlKit = MlKitOcrEngine()
    private val initMutex = Mutex()
    private val readMutex = Mutex()
    private val closeScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    @Volatile
    private var initialized = false

    private var tesseract: TesseractRowEngine? = null
    private var rapidOcr: RapidOcrRowEngine? = null
    private var templates: DigitTemplateEngine? = null
    private var rowReader: RowCropReader? = null
    private var rowReaderAll: RowCropReader? = null

    suspend fun warmUp() {
        if (initialized) return
        initMutex.withLock {
            if (initialized) return
            withContext(Dispatchers.IO) {
                tesseract = runCatching { TesseractRowEngine.create(context) }.getOrNull()
                rapidOcr = runCatching { RapidOcrRowEngine.create(context) }.getOrNull()
                templates = runCatching {
                    DigitTemplateStore.load(context)?.let { DigitTemplateEngine(it) }
                }.getOrNull()
            }
            rowReader = RowCropReader(
                mlKit = mlKit,
                tesseract = tesseract,
                rapidOcr = rapidOcr,
                templates = templates,
                cropSink = null,
                strategy = ReaderStrategy.CHEAP_FIRST,
                runTemplateForTiming = false
            )
            rowReaderAll = RowCropReader(
                mlKit = mlKit,
                tesseract = tesseract,
                rapidOcr = rapidOcr,
                templates = templates,
                cropSink = null,
                strategy = ReaderStrategy.ALL,
                runTemplateForTiming = false
            )
            initialized = true
        }
    }

    /**
     * Cheap anchor check used for detection. The parsed page and inspection are
     * reused for the first episode frame so the app never runs full-page OCR
     * twice on the same screenshot.
     */
    suspend fun checkResultsScreen(bitmap: Bitmap): ScreenCheck = readMutex.withLock {
        val page = mlKit.recognize(bitmap)
        val parser = ResultsScreenParser(BitmapIconColorSampler(bitmap))
        val inspection = parser.inspect(page, includeBalance = false)
        if (inspection != null) {
            ScreenCheck(true, null, page, inspection)
        } else {
            ScreenCheck(false, parser.ignoredReason(page), page, null)
        }
    }

    suspend fun isResultsScreen(bitmap: Bitmap): Boolean =
        checkResultsScreen(bitmap).isResults

    suspend fun read(
        bitmap: Bitmap,
        strategy: ReaderStrategy = ReaderStrategy.CHEAP_FIRST,
        includeBalance: Boolean = false
    ): ReadResult? {
        warmUp()
        return readMutex.withLock {
            val result = readLocked(bitmap, strategy, includeBalance) ?: return@withLock null
            if (result.inspection == null) return@withLock null
            ReadResult(
                inspection = result.inspection,
                page = result.page,
                rows = result.rows,
                read = result.read ?: return@withLock null,
                timingsMs = result.timingsMs
            )
        }
    }

    /**
     * One episode frame: full-page anchors plus the loot and bonus rows. Balance
     * rows are deliberately skipped; they are never part of the record and cost
     * three extra reader passes on every frame.
     */
    suspend fun readEpisode(
        bitmap: Bitmap,
        known: ScreenCheck? = null
    ): EpisodeRead? {
        warmUp()
        return readMutex.withLock {
            readLocked(bitmap, ReaderStrategy.CHEAP_FIRST, includeBalance = false, known = known)
        }
    }

    private suspend fun readLocked(
        bitmap: Bitmap,
        strategy: ReaderStrategy,
        includeBalance: Boolean,
        known: ScreenCheck? = null
    ): EpisodeRead? {
        val reader = if (strategy == ReaderStrategy.ALL) {
            rowReaderAll ?: rowReader
        } else {
            rowReader
        } ?: return null
        val pageStart = System.nanoTime()
        val page = known?.page ?: mlKit.recognize(bitmap)
        val pageOcrMs = if (known?.page != null) 0L else {
            (System.nanoTime() - pageStart) / 1_000_000L
        }
        val parser = ResultsScreenParser(BitmapIconColorSampler(bitmap))
        val inspection = known?.inspection ?: parser.inspect(page, includeBalance)
        if (inspection == null) {
            val timings = linkedMapOf(TimingKeys.MLKIT_FULL to pageOcrMs)
            return EpisodeRead(page, null, null, emptyList(), timings)
        }

        val rows = mutableListOf<RowReading>()
        for ((index, row) in inspection.lootRows.withIndex()) {
            rows += reader.read(bitmap, row, index, null, templateVoteEligible = true)
        }
        for ((index, row) in inspection.bonusRows.withIndex()) {
            rows += reader.read(bitmap, row, index, null, templateVoteEligible = true)
        }
        if (includeBalance) {
            for ((index, row) in inspection.balanceRows.withIndex()) {
                rows += reader.read(bitmap, row, index, null, templateVoteEligible = true)
            }
        }
        val read = ResultsAssembler.assemble(inspection, rows)
        val timings = linkedMapOf<String, Long>()
        for (row in rows) {
            for ((key, value) in row.timingsMs) {
                timings[key] = (timings[key] ?: 0L) + value
            }
        }
        timings[TimingKeys.MLKIT_FULL] = pageOcrMs
        return EpisodeRead(page, inspection, read, rows, timings)
    }

    /**
     * Full-page OCR for the screen-state engine and the wall bar reader. The
     * expensive engines were warmed by warmUp(), so callers only pay for the
     * recognition itself.
     */
    suspend fun page(bitmap: Bitmap): OcrPage {
        warmUp()
        return readMutex.withLock {
            mlKit.recognize(bitmap)
        }
    }

    /**
     * One number crop (home balance bars, wall-bar prices) through the same
     * consensus core as results rows. rawText is the full-page line when one
     * exists, else empty. Returns the agreed value or null when readers could
     * not agree.
     */
    suspend fun readNumber(
        bitmap: Bitmap,
        box: com.clahbot.ocr.OcrBox,
        rawText: String,
        resource: com.clahbot.ocr.ResourceKind?
    ): Long? {
        warmUp()
        return readMutex.withLock {
            val reader = rowReader ?: return@withLock null
            val row = com.clahbot.ocr.RowCandidate(
                box = box,
                rawText = rawText,
                role = com.clahbot.ocr.RowRole.BALANCE,
                resource = resource
            )
            reader.read(bitmap, row, 0, null, templateVoteEligible = true).combined
        }
    }

    fun closeAsync() {
        closeScope.launch {
            initMutex.withLock {
                readMutex.withLock {
                    initialized = false
                    rowReader = null
                    rowReaderAll = null
                    runCatching { tesseract?.close() }
                    runCatching { rapidOcr?.close() }
                    tesseract = null
                    rapidOcr = null
                    templates = null
                    runCatching { mlKit.close() }
                }
            }
        }
    }
}
