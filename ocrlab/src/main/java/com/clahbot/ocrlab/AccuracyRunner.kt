package com.clahbot.ocrlab

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import java.io.File
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

data class FieldScore(
    var expected: Int = 0,
    var captured: Int = 0,
    var correct: Int = 0,
    var wrong: Int = 0
) {
    val missing: Int get() = (expected - captured).coerceAtLeast(0)
    val percent: Double get() = if (expected == 0) 0.0 else 100.0 * correct / expected
}

data class EngineValues(
    val result: String?,
    val gold: Long?,
    val elixir: Long?,
    val dark: Long?,
    val bonusGold: Long?,
    val bonusElixir: Long?,
    val bonusDark: Long?,
    val balanceGold: Long?,
    val balanceElixir: Long?,
    val balanceDark: Long?
) {
    companion object {
        fun empty(): EngineValues = EngineValues(
            null, null, null, null, null, null, null, null, null, null
        )
    }
}

data class ScreenResult(
    val file: String,
    val expectedResult: String?,
    val detected: Boolean,
    val ignoredReason: String?,
    val engines: Map<String, EngineValues>,
    val combined: ResultsRead?,
    val fieldMatches: Map<String, Boolean>,
    val failures: List<String>,
    val rows: List<RowReading>,
    val balanceCheck: String,
    val screenshot: String?,
    val wrongCaptures: Int,
    val notCaptured: Int
)

data class DataSplit(
    val tuning: List<String>,
    val scoring: List<String>,
    val finalScoring: String,
    val cleanHoldout: Boolean,
    val note: String
)

data class AccuracyReport(
    val images: Int,
    val expectedResults: Int,
    val classificationCorrect: Int,
    val detectedResults: Int,
    val missedResults: Int,
    val falsePositives: Int,
    val fields: Map<String, FieldScore>,
    val engines: Map<String, Map<String, FieldScore>>,
    val screens: List<ScreenResult>,
    val duplicatesCounted: Int,
    val duplicatesIgnored: Int,
    val wrongCaptures: Int,
    val notCaptured: Int,
    val wrongCaptureList: List<String>,
    val runDirectory: String,
    val dataSplit: DataSplit,
    val notes: List<String>
) {
    fun summary(): String {
        val builder = StringBuilder()
        builder.appendLine("Images: $images")
        builder.appendLine("Screen classification: $classificationCorrect/$images")
        builder.appendLine("Results detected: $detectedResults/$expectedResults (missed $missedResults, false positives $falsePositives)")
        builder.appendLine("Wrong captures: $wrongCaptures")
        builder.appendLine("Not captured: $notCaptured")
        if (wrongCaptureList.isNotEmpty()) {
            builder.appendLine("Wrong capture details:")
            for (item in wrongCaptureList) builder.appendLine("  - $item")
        }
        builder.appendLine()
        appendFieldTable(builder, "Consensus field table", fields)
        builder.appendLine()
        for ((engine, scores) in engines) {
            appendFieldTable(builder, "Engine $engine", scores)
            val total = scores.values.fold(FieldScore()) { acc, score ->
                acc.expected += score.expected
                acc.captured += score.captured
                acc.correct += score.correct
                acc.wrong += score.wrong
                acc
            }
            builder.appendLine(
                String.format(
                    Locale.US,
                    "  total: expected=%d captured=%d correct=%d wrong=%d missing=%d (%.1f%%)",
                    total.expected,
                    total.captured,
                    total.correct,
                    total.wrong,
                    total.missing,
                    total.percent
                )
            )
            builder.appendLine()
        }
        builder.appendLine("Duplicates: counted $duplicatesCounted, ignored $duplicatesIgnored")
        builder.appendLine("Run directory: $runDirectory")
        builder.appendLine("Data split: tuning=${dataSplit.tuning.size}, scoring=${dataSplit.scoring.size}, cleanHoldout=${dataSplit.cleanHoldout}")
        for (note in notes) builder.appendLine("Note: $note")
        builder.appendLine()
        for (screen in screens) {
            builder.appendLine(
                "${screen.file}: " +
                    if (screen.detected) {
                        val outcome = screen.combined?.outcome?.name?.lowercase(Locale.US) ?: "?"
                        "detected $outcome " +
                            "G=${screen.combined?.gold ?: "-"} " +
                            "E=${screen.combined?.elixir ?: "-"} " +
                            "D=${screen.combined?.darkElixir ?: "-"} " +
                            "BG=${screen.combined?.bonusGold ?: "-"} " +
                            "BE=${screen.combined?.bonusElixir ?: "-"} " +
                            "BD=${screen.combined?.bonusDarkElixir ?: "-"} " +
                            "balance=${screen.balanceCheck} " +
                            "wrong=${screen.wrongCaptures} missing=${screen.notCaptured}"
                    } else {
                        "ignored (${screen.ignoredReason ?: "no reason"})"
                    }
            )
            if (screen.failures.isNotEmpty()) {
                builder.appendLine("  FAIL: ${screen.failures.joinToString("; ")}")
            }
            for (row in screen.rows) {
                builder.appendLine(
                    "  ${row.role.name.lowercase(Locale.US)} ${row.index + 1} " +
                        "${row.resource?.name ?: "?"}: " +
                        "combined=${row.combined ?: "-"} " +
                        "sources=${row.sources.joinToString("/").ifEmpty { "-" }} " +
                        "status=${row.status}" +
                        if (row.expectedFromMath != null) " math=${row.expectedFromMath}" else ""
                )
                for (read in row.reads) {
                    builder.appendLine(
                        "    ${read.reader}: '${read.text}' " +
                            "candidates=${read.candidates.joinToString(",").ifEmpty { "-" }} " +
                            "chosen=${read.chosen ?: "-"}" +
                            if (read.error != null) " error=${read.error}" else ""
                    )
                }
            }
        }
        return builder.toString()
    }

    private fun appendFieldTable(
        builder: StringBuilder,
        title: String,
        scores: Map<String, FieldScore>
    ) {
        builder.appendLine("$title:")
        if (scores.isEmpty()) {
            builder.appendLine("  (unavailable)")
            return
        }
        builder.appendLine(
            String.format(
                Locale.US,
                "  %-16s %8s %8s %7s %7s %8s %9s",
                "Field", "Expected", "Captured", "Correct", "Wrong", "Missing", "Accuracy"
            )
        )
        for (name in FIELD_ORDER) {
            val score = scores[name] ?: continue
            builder.appendLine(
                String.format(
                    Locale.US,
                    "  %-16s %8d %8d %7d %7d %8d %8.1f%%",
                    name,
                    score.expected,
                    score.captured,
                    score.correct,
                    score.wrong,
                    score.missing,
                    score.percent
                )
            )
        }
    }

    companion object {
        val FIELD_ORDER = listOf(
            "result",
            "gold",
            "elixir",
            "dark",
            "bonus_gold",
            "bonus_elixir",
            "bonus_dark",
            "balance_gold",
            "balance_elixir",
            "balance_dark"
        )
    }
}

/**
 * Runs the accuracy pack through five readers per row: full-page ML Kit,
 * crop ML Kit, Tesseract, RapidOCR and digit templates. A final value needs a
 * reader consensus or exact balance arithmetic; single-reader values are
 * reported as missing. Every crop is written under the run directory.
 */
class AccuracyRunner(private val context: Context) {

    private data class Comparison(
        val name: String,
        val expected: Any?,
        val actual: Any?
    ) {
        val wrong: Boolean get() = actual != null && actual != expected
        val missing: Boolean get() = actual == null
        fun failure(): String = if (actual == null) {
            "$name missing (expected=$expected)"
        } else {
            "$name expected=$expected actual=$actual"
        }
    }

    suspend fun run(directory: File, ocr: OcrEngine): AccuracyReport {
        val truthFile = File(directory, "ground_truth.json")
        require(truthFile.isFile) { "ground_truth.json missing" }
        val truth = GroundTruth.load(truthFile)
        require(truth.isNotEmpty()) { "ground_truth.json has no results_screens" }

        val images = directory.listFiles()
            ?.filter { it.isFile && it.extension.lowercase(Locale.US) in IMAGE_EXTENSIONS }
            ?.sortedBy { it.name }
            .orEmpty()
        require(images.isNotEmpty()) { "no screenshots in ${directory.absolutePath}" }

        val runDirectory = File(
            context.getExternalFilesDir(null) ?: context.filesDir,
            "accuracy_runs/${System.currentTimeMillis()}"
        ).apply { mkdirs() }
        runCatching {
            truthFile.copyTo(File(runDirectory, "ground_truth.json"), overwrite = true)
        }

        val fields = linkedMapOf<String, FieldScore>()
        val engines = linkedMapOf<String, LinkedHashMap<String, FieldScore>>()
        for (engine in ENGINE_NAMES) engines[engine] = linkedMapOf()
        val screens = mutableListOf<ScreenResult>()
        var classificationCorrect = 0
        var detectedResults = 0
        var missedResults = 0
        var falsePositives = 0
        var duplicatesCounted = 0
        var duplicatesIgnored = 0
        var searchCostGold: Long? = null
        var previousResult: ResultsRead? = null
        val gate = ResultsGate(requiredAgreement = 2)
        val wrongCaptureList = mutableListOf<String>()

        val notes = mutableListOf<String>()
        notes += "Numeric parsing is digits-only with optional grouping; confusable letters expand to candidate digits and labels are separated from numbers."
        notes += "Loot rows must be in the right-aligned value column and have a recognised resource icon, so the Sour Elixir panel is excluded."
        notes += "A value is accepted only with at least two independent readers, or one reader plus an exact balance-identity match; a lone reader is never accepted."
        notes += "RapidOCR is PP-OCRv4 rec (PaddleOCR) on ONNX Runtime; digit templates are 24x32 neutral-channel glyphs extracted from the labelled pack (leave-one-row-out 199/199)."
        notes += "The exact crop fed to each reader is saved under the run directory."
        notes += "Balance cross-check is applied only between a defeat and the next victory when all balances, bonuses and a search cost are available; a mismatch is needs-review, not a value substitution."
        notes += "Stars are not parsed by this spike; stored as unknown for now."
        notes += "The 11-image pack informed all fixes and the templates, so it is not a clean holdout. The separate 30+ set is the final scoring set."

        val tesseract = try {
            TesseractRowEngine.create(context)
        } catch (_: Throwable) {
            notes += "Tesseract failed to initialize; Tesseract crop fields are unavailable in this run."
            null
        }
        val rapidOcr = try {
            RapidOcrRowEngine.create(context)
        } catch (throwable: Throwable) {
            notes += "RapidOCR failed to initialize: ${throwable.message ?: throwable.javaClass.simpleName}"
            null
        }
        val templates = try {
            DigitTemplateStore.load(context)?.let { DigitTemplateEngine(it) }
        } catch (throwable: Throwable) {
            notes += "Digit templates failed to load: ${throwable.message ?: throwable.javaClass.simpleName}"
            null
        }
        if (templates == null) notes += "Digit-template fields are unavailable in this run."

        try {
            for (file in images) {
                val bitmap = withContext(Dispatchers.IO) {
                    runCatching { BitmapFactory.decodeFile(file.absolutePath) }.getOrNull()
                }
                if (bitmap == null) {
                    screens += ScreenResult(
                        file = file.name,
                        expectedResult = truth[file.name]?.result,
                        detected = false,
                        ignoredReason = "DECODE_FAILED",
                        engines = emptyMap(),
                        combined = null,
                        fieldMatches = emptyMap(),
                        failures = listOf("image could not be decoded"),
                        rows = emptyList(),
                        balanceCheck = "not_available",
                        screenshot = null,
                        wrongCaptures = 0,
                        notCaptured = 0
                    )
                    continue
                }

                val expected = truth[file.name]
                val page = try {
                    ocr.recognize(bitmap)
                } catch (throwable: Throwable) {
                    screens += ScreenResult(
                        file = file.name,
                        expectedResult = expected?.result,
                        detected = false,
                        ignoredReason = "OCR_FAILED",
                        engines = emptyMap(),
                        combined = null,
                        fieldMatches = emptyMap(),
                        failures = listOf("full-page OCR failed: ${throwable.message}"),
                        rows = emptyList(),
                        balanceCheck = "not_available",
                        screenshot = saveFailure(runDirectory, file),
                        wrongCaptures = 0,
                        notCaptured = 0
                    )
                    bitmap.recycle()
                    continue
                }

                val parser = ResultsScreenParser(BitmapIconColorSampler(bitmap))
                val inspection = parser.inspect(page)

                if (inspection == null) {
                    readSearchCost(page)?.let { searchCostGold = it }
                    if (expected == null) {
                        classificationCorrect++
                        screens += ScreenResult(
                            file = file.name,
                            expectedResult = null,
                            detected = false,
                            ignoredReason = parser.ignoredReason(page),
                            engines = emptyMap(),
                            combined = null,
                            fieldMatches = emptyMap(),
                            failures = emptyList(),
                            rows = emptyList(),
                            balanceCheck = "not_available",
                            screenshot = null,
                            wrongCaptures = 0,
                            notCaptured = 0
                        )
                    } else {
                        missedResults++
                        val comparisons = finalComparisons(expected, null)
                        scoreAll(fields, comparisons)
                        val emptyValues = EngineValues.empty()
                        for (engine in ENGINE_NAMES) {
                            scoreAll(
                                engines.getValue(engine),
                                engineComparisons(expected, engine, emptyValues)
                            )
                        }
                        screens += ScreenResult(
                            file = file.name,
                            expectedResult = expected.result,
                            detected = false,
                            ignoredReason = parser.ignoredReason(page),
                            engines = ENGINE_NAMES.associateWith { emptyValues },
                            combined = null,
                            fieldMatches = comparisons.associate { it.name to false },
                            failures = comparisons.map { it.failure() },
                            rows = emptyList(),
                            balanceCheck = "not_available",
                            screenshot = saveFailure(runDirectory, file),
                            wrongCaptures = 0,
                            notCaptured = comparisons.count { it.missing }
                        )
                    }
                    gate.observe(null)
                    previousResult = null
                    bitmap.recycle()
                    continue
                }

                val screenDirectory = File(
                    runDirectory,
                    "crops/${file.nameWithoutExtension.replace(Regex("[^A-Za-z0-9._-]"), "_")}"
                )
                val cropSink = CropSink(screenDirectory)
                val fullPageCrop = cropSink.save("full_mlkit", bitmap)
                val cropReader = RowCropReader(ocr, tesseract, rapidOcr, templates, cropSink)
                val readings = mutableListOf<RowReading>()
                for ((index, row) in inspection.lootRows.withIndex()) {
                    readings += cropReader.read(bitmap, row, index, fullPageCrop)
                }
                for ((index, row) in inspection.bonusRows.withIndex()) {
                    readings += cropReader.read(bitmap, row, index, fullPageCrop)
                }
                for ((index, row) in inspection.balanceRows.withIndex()) {
                    readings += cropReader.read(bitmap, row, index, fullPageCrop)
                }

                var finalRead = ResultsAssembler.assemble(inspection, readings)
                val math = BalanceMath.expectedLoot(previousResult, finalRead, searchCostGold)
                if (math != null && math.any) {
                    val updated = readings.map { row ->
                        val expectedLoot = row.resource?.let { math.value(it) }
                        if (row.role == RowRole.LOOT &&
                            row.combined == null &&
                            expectedLoot != null
                        ) {
                            val resolved = RowConsensus.resolve(row.reads, expectedLoot)
                            if (resolved.value != null) {
                                row.withResult(expectedLoot, resolved)
                            } else {
                                row
                            }
                        } else {
                            row
                        }
                    }
                    if (updated != readings) {
                        readings.clear()
                        readings += updated
                        finalRead = ResultsAssembler.assemble(inspection, readings)
                    }
                }

                val balanceCheck = BalanceMath.crossCheck(previousResult, finalRead, searchCostGold)
                if (balanceCheck == "mismatch") {
                    finalRead = finalRead.copy(
                        needsReview = true,
                        issues = finalRead.issues + "balance cross-check mismatch"
                    )
                }

                val engineValues = linkedMapOf<String, EngineValues>()
                for (engine in ENGINE_NAMES) {
                    var values = valuesForEngine(readings, engine)
                    if (engine == Readers.MLKIT_FULL) {
                        values = values.copy(
                            result = inspection.outcome.name.lowercase(Locale.US)
                        )
                    } else if (engine == Readers.CONSENSUS) {
                        values = values.copy(
                            result = finalRead.outcome.name.lowercase(Locale.US)
                        )
                    }
                    engineValues[engine] = values
                }

                val comparisons = if (expected != null) {
                    finalComparisons(expected, finalRead)
                } else {
                    emptyList()
                }
                if (expected != null) {
                    scoreAll(fields, comparisons)
                    for (engine in ENGINE_NAMES) {
                        scoreAll(
                            engines.getValue(engine),
                            engineComparisons(expected, engine, engineValues.getValue(engine))
                        )
                    }
                }
                val wrong = comparisons.filter { it.wrong }
                wrongCaptureList += wrong.map { "${file.name} ${it.failure()}" }
                val screenWrong = wrong.size
                val screenMissing = comparisons.count { it.missing }

                val screenshot = if (expected == null) {
                    saveFailure(runDirectory, file)
                } else if (!finalRead.lootComplete || finalRead.needsReview) {
                    saveFailure(runDirectory, file)
                } else {
                    null
                }

                if (expected != null) {
                    classificationCorrect++
                    detectedResults++
                    feedResult(gate, finalRead) { duplicatesIgnored++ }.let { duplicatesCounted += it }
                    previousResult = finalRead
                } else {
                    falsePositives++
                    feedResult(gate, finalRead) { duplicatesIgnored++ }.let { duplicatesCounted += it }
                    previousResult = null
                }

                screens += ScreenResult(
                    file = file.name,
                    expectedResult = expected?.result,
                    detected = true,
                    ignoredReason = null,
                    engines = engineValues,
                    combined = finalRead,
                    fieldMatches = comparisons.associate { it.name to (it.actual == it.expected) },
                    failures = if (expected == null) {
                        listOf("unexpected results screen")
                    } else {
                        comparisons.filter { it.actual != it.expected }.map { it.failure() }
                    },
                    rows = readings,
                    balanceCheck = balanceCheck,
                    screenshot = screenshot,
                    wrongCaptures = screenWrong,
                    notCaptured = screenMissing
                )
                bitmap.recycle()
            }
        } finally {
            tesseract?.close()
            rapidOcr?.close()
        }

        val orderedFields = linkedMapOf<String, FieldScore>()
        for (name in AccuracyReport.FIELD_ORDER) {
            fields[name]?.let { orderedFields[name] = it }
        }
        for ((name, score) in fields) {
            if (!orderedFields.containsKey(name)) orderedFields[name] = score
        }

        return AccuracyReport(
            images = images.size,
            expectedResults = truth.size,
            classificationCorrect = classificationCorrect,
            detectedResults = detectedResults,
            missedResults = missedResults,
            falsePositives = falsePositives,
            fields = orderedFields,
            engines = engines,
            screens = screens,
            duplicatesCounted = duplicatesCounted,
            duplicatesIgnored = duplicatesIgnored,
            wrongCaptures = orderedFields.values.sumOf { it.wrong },
            notCaptured = orderedFields.values.sumOf { it.missing },
            wrongCaptureList = wrongCaptureList,
            runDirectory = runDirectory.absolutePath,
            dataSplit = DataSplit(
                tuning = listOf(
                    "08_results_victory_2star_70.jpg",
                    "09_results_defeat_35.jpg"
                ),
                scoring = listOf(
                    "10_results_victory_3star_100.jpg",
                    "11_results_victory_2star_71_reference.jpg"
                ),
                finalScoring = "The 30+ screenshots you will supply, not used for tuning.",
                cleanHoldout = false,
                note = "The 11 pack informed every fix and the digit templates, so the scoring split is a regression check, not a clean holdout."
            ),
            notes = notes
        )
    }

    private fun valuesForEngine(rows: List<RowReading>, engine: String): EngineValues {
        var gold: Long? = null
        var elixir: Long? = null
        var dark: Long? = null
        var bonusGold: Long? = null
        var bonusElixir: Long? = null
        var bonusDark: Long? = null
        var balanceGold: Long? = null
        var balanceElixir: Long? = null
        var balanceDark: Long? = null
        for (row in rows) {
            val value = if (engine == Readers.CONSENSUS) {
                row.combined
            } else {
                row.reads.firstOrNull { it.reader == engine }?.chosen
            } ?: continue
            val resource = row.resource ?: continue
            when (row.role) {
                RowRole.LOOT -> when (resource) {
                    ResourceKind.GOLD -> gold = gold ?: value
                    ResourceKind.ELIXIR -> elixir = elixir ?: value
                    ResourceKind.DARK -> dark = dark ?: value
                }

                RowRole.BONUS -> when (resource) {
                    ResourceKind.GOLD -> bonusGold = bonusGold ?: value
                    ResourceKind.ELIXIR -> bonusElixir = bonusElixir ?: value
                    ResourceKind.DARK -> bonusDark = bonusDark ?: value
                }

                RowRole.BALANCE -> when (resource) {
                    ResourceKind.GOLD -> balanceGold = balanceGold ?: value
                    ResourceKind.ELIXIR -> balanceElixir = balanceElixir ?: value
                    ResourceKind.DARK -> balanceDark = balanceDark ?: value
                }
            }
        }
        return EngineValues(
            result = null,
            gold = gold,
            elixir = elixir,
            dark = dark,
            bonusGold = bonusGold,
            bonusElixir = bonusElixir,
            bonusDark = bonusDark,
            balanceGold = balanceGold,
            balanceElixir = balanceElixir,
            balanceDark = balanceDark
        )
    }

    private fun finalComparisons(
        expected: GroundTruth,
        read: ResultsRead?
    ): List<Comparison> {
        val result = mutableListOf<Comparison>()
        result += Comparison(
            "result",
            expected.result,
            read?.outcome?.name?.lowercase(Locale.US)
        )
        expected.gold?.let { result += Comparison("gold", it, read?.gold) }
        expected.elixir?.let { result += Comparison("elixir", it, read?.elixir) }
        expected.darkElixir?.let { result += Comparison("dark", it, read?.darkElixir) }
        expected.bonusGold?.let { result += Comparison("bonus_gold", it, read?.bonusGold) }
        expected.bonusElixir?.let { result += Comparison("bonus_elixir", it, read?.bonusElixir) }
        expected.bonusDarkElixir?.let {
            result += Comparison("bonus_dark", it, read?.bonusDarkElixir)
        }
        expected.balanceGold?.let {
            result += Comparison("balance_gold", it, read?.balanceGold)
        }
        expected.balanceElixir?.let {
            result += Comparison("balance_elixir", it, read?.balanceElixir)
        }
        expected.balanceDarkElixir?.let {
            result += Comparison("balance_dark", it, read?.balanceDarkElixir)
        }
        return result
    }

    private fun engineComparisons(
        expected: GroundTruth,
        engine: String,
        values: EngineValues
    ): List<Comparison> {
        val result = mutableListOf<Comparison>()
        val readsResult = engine == Readers.MLKIT_FULL || engine == Readers.CONSENSUS
        if (readsResult) {
            result += Comparison("result", expected.result, values.result)
        }
        expected.gold?.let { result += Comparison("gold", it, values.gold) }
        expected.elixir?.let { result += Comparison("elixir", it, values.elixir) }
        expected.darkElixir?.let { result += Comparison("dark", it, values.dark) }
        expected.bonusGold?.let { result += Comparison("bonus_gold", it, values.bonusGold) }
        expected.bonusElixir?.let {
            result += Comparison("bonus_elixir", it, values.bonusElixir)
        }
        expected.bonusDarkElixir?.let {
            result += Comparison("bonus_dark", it, values.bonusDark)
        }
        expected.balanceGold?.let {
            result += Comparison("balance_gold", it, values.balanceGold)
        }
        expected.balanceElixir?.let {
            result += Comparison("balance_elixir", it, values.balanceElixir)
        }
        expected.balanceDarkElixir?.let {
            result += Comparison("balance_dark", it, values.balanceDark)
        }
        return result
    }

    private fun scoreAll(target: MutableMap<String, FieldScore>, comparisons: List<Comparison>) {
        for (comparison in comparisons) {
            val score = target.getOrPut(comparison.name) { FieldScore() }
            score.expected++
            if (comparison.actual != null) score.captured++
            if (comparison.expected == comparison.actual) {
                score.correct++
            } else if (comparison.actual != null) {
                score.wrong++
            }
        }
    }

    private fun readSearchCost(page: OcrPage): Long? {
        val anchors = page.lines
            .filter { normalized(it.text).contains("findamatch") }
            .sortedBy { it.box?.centerX ?: Int.MAX_VALUE }
        for (anchor in anchors) {
            val anchorBox = anchor.box ?: continue
            val minX = anchorBox.centerX - page.width * 0.08
            val maxX = anchorBox.centerX + page.width * 0.08
            val best = page.lines.mapNotNull { line ->
                val box = line.box ?: return@mapNotNull null
                if (line.text.contains("/")) return@mapNotNull null
                if (box.centerX < minX || box.centerX > maxX) return@mapNotNull null
                val distance = box.centerY - anchorBox.bottom
                if (distance < 0 || distance > page.height * 0.18) return@mapNotNull null
                val value = NumberParser.searchCostCandidates(line.text)
                    .map { it.value }
                    .firstOrNull { it in 100..999_999 }
                    ?: return@mapNotNull null
                value to distance
            }.minByOrNull { it.second }?.first
            if (best != null) return best
        }
        return null
    }

    private fun feedResult(
        gate: ResultsGate,
        read: ResultsRead,
        onDuplicate: () -> Unit
    ): Int {
        var counted = 0
        when (gate.observe(read)) {
            is GateResult.Counted -> counted++
            is GateResult.Duplicate -> onDuplicate()
            else -> Unit
        }
        when (gate.observe(read)) {
            is GateResult.Counted -> counted++
            is GateResult.Duplicate -> onDuplicate()
            else -> Unit
        }
        when (gate.observe(read)) {
            is GateResult.Duplicate -> onDuplicate()
            else -> Unit
        }
        gate.observe(null)
        return counted
    }

    private fun saveFailure(runDirectory: File, file: File): String? {
        return try {
            val directory = File(runDirectory, "failures").apply { mkdirs() }
            val destination = File(directory, "${System.currentTimeMillis()}-${file.name}")
            file.copyTo(destination, overwrite = true)
            destination.absolutePath
        } catch (_: Throwable) {
            null
        }
    }

    private fun normalized(text: String): String =
        text.lowercase(Locale.US).filter { it.isLetterOrDigit() }

    companion object {
        private val IMAGE_EXTENSIONS = setOf("jpg", "jpeg", "png", "webp")
        private val ENGINE_NAMES = listOf(
            Readers.MLKIT_FULL,
            Readers.MLKIT_CROP,
            Readers.TESSERACT_CROP,
            Readers.RAPIDOCR_CROP,
            Readers.TEMPLATE_CROP,
            Readers.CONSENSUS
        )
    }
}
