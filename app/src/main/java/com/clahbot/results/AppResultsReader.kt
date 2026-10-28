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

    data class ScreenCheck(val isResults: Boolean, val reason: String?)

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
     * Cheap anchor check used only for the pre-playback baseline. It never
     * reads loot and never creates a record.
     */
    suspend fun checkResultsScreen(bitmap: Bitmap): ScreenCheck = readMutex.withLock {
        val page = mlKit.recognize(bitmap)
        val parser = ResultsScreenParser(BitmapIconColorSampler(bitmap))
        val inspection = parser.inspect(page)
        if (inspection != null) {
            ScreenCheck(true, null)
        } else {
            ScreenCheck(false, parser.ignoredReason(page))
        }
    }

    suspend fun isResultsScreen(bitmap: Bitmap): Boolean =
        checkResultsScreen(bitmap).isResults

    suspend fun read(
        bitmap: Bitmap,
        strategy: ReaderStrategy = ReaderStrategy.CHEAP_FIRST
    ): ReadResult? {
        warmUp()
        return readMutex.withLock {
            val reader = if (strategy == ReaderStrategy.ALL) {
                rowReaderAll ?: rowReader
            } else {
                rowReader
            } ?: return@withLock null
            val pageStart = System.nanoTime()
            val page = mlKit.recognize(bitmap)
            val pageOcrMs = (System.nanoTime() - pageStart) / 1_000_000L
            val parser = ResultsScreenParser(BitmapIconColorSampler(bitmap))
            val inspection = parser.inspect(page) ?: return@withLock null

            val rows = mutableListOf<RowReading>()
            for ((index, row) in inspection.lootRows.withIndex()) {
                rows += reader.read(bitmap, row, index, null, templateVoteEligible = true)
            }
            for ((index, row) in inspection.bonusRows.withIndex()) {
                rows += reader.read(bitmap, row, index, null, templateVoteEligible = true)
            }
            for ((index, row) in inspection.balanceRows.withIndex()) {
                rows += reader.read(bitmap, row, index, null, templateVoteEligible = true)
            }
            val read = ResultsAssembler.assemble(inspection, rows)
            val timings = linkedMapOf<String, Long>()
            for (row in rows) {
                for ((key, value) in row.timingsMs) {
                    timings[key] = (timings[key] ?: 0L) + value
                }
            }
            timings[TimingKeys.MLKIT_FULL] = pageOcrMs
            ReadResult(
                inspection = inspection,
                page = page,
                rows = rows,
                read = read,
                timingsMs = timings
            )
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
