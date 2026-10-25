package com.clahbot.ocrlab

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import java.io.File
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

data class FieldScore(
    var correct: Int = 0,
    var total: Int = 0
) {
    val percent: Double
        get() = if (total == 0) 0.0 else 100.0 * correct / total
}

data class EngineValues(
    val gold: Long?,
    val elixir: Long?,
    val dark: Long?,
    val bonusGold: Long?,
    val bonusElixir: Long?,
    val bonusDark: Long?,
    val balanceGold: Long?,
    val balanceElixir: Long?,
    val balanceDark: Long?
)

data class ScreenResult(
    val file: String,
    val expectedResult: String?,
    val detected: Boolean,
    val ignoredReason: String?,
    val mlKitFull: ResultsRead?,
    val mlKitCrop: EngineValues?,
    val tesseractCrop: EngineValues?,
    val combined: ResultsRead?,
    val fieldMatches: Map<String, Boolean>,
    val failures: List<String>,
    val rows: List<RowReading>,
    val balanceCheck: String,
    val screenshot: String?
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
    val falsePositives: Int,
    val fields: Map<String, FieldScore>,
    val engines: Map<String, Map<String, FieldScore>>,
    val screens: List<ScreenResult>,
    val duplicatesCounted: Int,
    val duplicatesIgnored: Int,
    val dataSplit: DataSplit,
    val notes: List<String>
) {
    fun summary(): String {
        val builder = StringBuilder()
        builder.appendLine("Images: $images")
        builder.appendLine("Screen classification: $classificationCorrect/$images")
        builder.appendLine("Results detected: $detectedResults/$expectedResults (false positives $falsePositives)")
        builder.appendLine("Combined fields:")
        appendFields(builder, fields)
        for ((engine, scores) in engines) {
            if (engine == "combined") continue
            builder.appendLine("$engine fields:")
            appendFields(builder, scores)
        }
        builder.appendLine("Duplicates: counted $duplicatesCounted, ignored $duplicatesIgnored")
        builder.appendLine("Data split: tuning=${dataSplit.tuning.size}, scoring=${dataSplit.scoring.size}, cleanHoldout=${dataSplit.cleanHoldout}")
        for (note in notes) builder.appendLine("Note: $note")
        builder.appendLine()
        for (screen in screens) {
            builder.appendLine(
                "${screen.file}: " +
                    if (screen.detected) {
                        "detected ${screen.combined?.outcome?.name?.lowercase(Locale.US)} " +
                            "G=${screen.combined?.gold ?: "-"} " +
                            "E=${screen.combined?.elixir ?: "-"} " +
                            "D=${screen.combined?.darkElixir ?: "-"} " +
                            "BG=${screen.combined?.bonusGold ?: "-"} " +
                            "BE=${screen.combined?.bonusElixir ?: "-"} " +
                            "BD=${screen.combined?.bonusDarkElixir ?: "-"} " +
                            "balance=${screen.balanceCheck}"
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
                        "ml='${row.mlKit.text}'=${row.mlKit.value ?: "-"} | " +
                        "tess='${row.tesseract.text}'=${row.tesseract.value ?: "-"} | " +
                        "combined=${row.combined ?: "-"} (${row.source})"
                )
            }
        }
        return builder.toString()
    }

    private fun appendFields(builder: StringBuilder, scores: Map<String, FieldScore>) {
        for ((name, score) in scores) {
            builder.appendLine(
                String.format(
                    Locale.US,
                    "  %s: %d/%d (%.1f%%)",
                    name,
                    score.correct,
                    score.total,
                    score.percent
                )
            )
        }
    }
}

/**
 * Runs the full 11-image pack. ML Kit and Tesseract both read every loot,
 * bonus and balance row from a 3x upscaled crop; the final value is the agreed
 * value, or the only valid engine value, and a conflict becomes "not captured".
 */
class AccuracyRunner(private val context: Context) {

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

        val fields = linkedMapOf<String, FieldScore>()
        val engines = linkedMapOf(
            "mlkit_full" to linkedMapOf<String, FieldScore>(),
            "mlkit_crop" to linkedMapOf<String, FieldScore>(),
            "tesseract_crop" to linkedMapOf<String, FieldScore>(),
            "combined" to linkedMapOf<String, FieldScore>()
        )
        val screens = mutableListOf<ScreenResult>()
        var classificationCorrect = 0
        var detectedResults = 0
        var falsePositives = 0
        val gate = ResultsGate(requiredAgreement = 2)
        var duplicatesCounted = 0
        var duplicatesIgnored = 0
        var searchCostGold: Long? = null
        var previousResult: ResultsRead? = null

        val tesseract = try {
            TesseractRowEngine.create(context)
        } catch (_: Throwable) {
            null
        }
        val cropReader = RowCropReader(ocr, tesseract)
        val notes = mutableListOf<String>()
        notes += "Loot/balance values require the grouped 'xxx xxx' format; bonuses use the ungrouped +N panel format."
        notes += "A format failure or engine conflict is not captured (null), never 0 or a guessed value."
        notes += "Balance cross-check is applied only when a previous defeat result and a read search cost are available; a mismatch is needs-review, not a value substitution."
        notes += "Stars are not parsed by this spike; stored as unknown for now."
        notes += "The 11-image pack is a development/regression set; fixes were informed by its first pass, so this is not a clean holdout. The separate 30+ set is the final scoring set."
        if (tesseract == null) notes += "Tesseract failed to initialize; Tesseract crop fields are unavailable in this run."

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
                        mlKitFull = null,
                        mlKitCrop = null,
                        tesseractCrop = null,
                        combined = null,
                        fieldMatches = emptyMap(),
                        failures = listOf("image could not be decoded"),
                        rows = emptyList(),
                        balanceCheck = "not_available",
                        screenshot = null
                    )
                    continue
                }

                val page = try {
                    ocr.recognize(bitmap)
                } catch (t: Throwable) {
                    bitmap.recycle()
                    screens += ScreenResult(
                        file = file.name,
                        expectedResult = truth[file.name]?.result,
                        detected = false,
                        ignoredReason = "OCR_FAILED",
                        mlKitFull = null,
                        mlKitCrop = null,
                        tesseractCrop = null,
                        combined = null,
                        fieldMatches = emptyMap(),
                        failures = listOf("full-page OCR failed: ${t.message}"),
                        rows = emptyList(),
                        balanceCheck = "not_available",
                        screenshot = saveFailure(file)
                    )
                    continue
                }
                val parser = ResultsScreenParser(BitmapIconColorSampler(bitmap))
                val inspection = parser.inspect(page)
                val expected = truth[file.name]

                if (inspection == null) {
                    if (page.lines.any {
                            normalized(it.text).contains("findamatch")
                        }
                    ) {
                        readSearchCost(page)?.let { searchCostGold = it }
                    }
                    if (expected == null) {
                        classificationCorrect++
                    } else {
                        falsePositives++
                    }
                    gate.observe(null)
                    previousResult = null
                    val ignored = (parser.parse(page) as? ScreenRead.Ignored)?.reason
                        ?: "not a results screen"
                    screens += ScreenResult(
                        file = file.name,
                        expectedResult = expected?.result,
                        detected = false,
                        ignoredReason = ignored,
                        mlKitFull = null,
                        mlKitCrop = null,
                        tesseractCrop = null,
                        combined = null,
                        fieldMatches = emptyMap(),
                        failures = if (expected == null) emptyList()
                        else listOf("result screen not detected"),
                        rows = emptyList(),
                        balanceCheck = "not_available",
                        screenshot = if (expected == null) null else saveFailure(file)
                    )
                    bitmap.recycle()
                    continue
                }

                val fullRead = (parser.parse(page) as? ScreenRead.Results)?.read
                val rows = mutableListOf<RowReading>()
                for ((index, row) in inspection.lootRows.withIndex()) {
                    rows += cropReader.read(bitmap, row, index)
                }
                for ((index, row) in inspection.bonusRows.withIndex()) {
                    rows += cropReader.read(bitmap, row, index)
                }
                for ((index, row) in inspection.balanceRows.withIndex()) {
                    rows += cropReader.read(bitmap, row, index)
                }

                val mlKitCrop = engineValues(rows) { it.mlKit.value }
                val tesseractCrop = engineValues(rows) { it.tesseract.value }
                val combinedValues = engineValues(rows) { it.combined }
                var combined = parser.buildRead(
                    inspection = inspection,
                    lootValues = valuesFor(rows, RowRole.LOOT) { it.combined },
                    bonusValues = valuesFor(rows, RowRole.BONUS) { it.combined },
                    balances = balancesFor(rows)
                )

                val balanceCheck = crossCheck(previousResult, combined, searchCostGold)
                if (balanceCheck == "mismatch") {
                    combined = combined.copy(
                        needsReview = true,
                        issues = combined.issues + "balance cross-check mismatch"
                    )
                }
                previousResult = combined

                if (expected != null) {
                    classificationCorrect++
                    detectedResults++
                    scoreRead(engines.getValue("mlkit_full"), expected, fullRead)
                    scoreValues(engines.getValue("mlkit_crop"), expected, mlKitCrop, combined.outcome)
                    scoreValues(engines.getValue("tesseract_crop"), expected, tesseractCrop, combined.outcome)
                    scoreRead(engines.getValue("combined"), expected, combined)
                    scoreRead(fields, expected, combined)
                } else {
                    falsePositives++
                }

                duplicatesCounted += feedResult(gate, combined) { duplicatesIgnored++ }

                val matches = linkedMapOf<String, Boolean>()
                val failures = mutableListOf<String>()
                if (expected != null) {
                    compareFields(matches, failures, expected, combined)
                }
                val screenshot = if (combined.lootComplete && !combined.needsReview) {
                    null
                } else {
                    saveFailure(file)
                }
                screens += ScreenResult(
                    file = file.name,
                    expectedResult = expected?.result,
                    detected = true,
                    ignoredReason = null,
                    mlKitFull = fullRead,
                    mlKitCrop = mlKitCrop,
                    tesseractCrop = tesseractCrop,
                    combined = combined,
                    fieldMatches = matches,
                    failures = failures,
                    rows = rows,
                    balanceCheck = balanceCheck,
                    screenshot = screenshot
                )
                bitmap.recycle()
            }
        } finally {
            tesseract?.close()
        }

        return AccuracyReport(
            images = images.size,
            expectedResults = truth.size,
            classificationCorrect = classificationCorrect,
            detectedResults = detectedResults,
            falsePositives = falsePositives,
            fields = fields,
            engines = engines,
            screens = screens,
            duplicatesCounted = duplicatesCounted,
            duplicatesIgnored = duplicatesIgnored,
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
                note = "The 11 pack informed the fixes, so the scoring split is a regression check, not a clean holdout."
            ),
            notes = notes
        )
    }

    private fun engineValues(
        rows: List<RowReading>,
        selector: (RowReading) -> Long?
    ): EngineValues {
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
            val value = selector(row) ?: continue
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
            gold, elixir, dark,
            bonusGold, bonusElixir, bonusDark,
            balanceGold, balanceElixir, balanceDark
        )
    }

    private fun valuesFor(
        rows: List<RowReading>,
        role: RowRole,
        selector: (RowReading) -> Long?
    ): List<Long?> = rows.filter { it.role == role }.map(selector)

    private fun balancesFor(rows: List<RowReading>): Triple<Long?, Long?, Long?> {
        val balanceRows = rows.filter { it.role == RowRole.BALANCE }
        fun value(resource: ResourceKind): Long? =
            balanceRows.firstOrNull { it.resource == resource }?.combined
        return Triple(
            value(ResourceKind.GOLD),
            value(ResourceKind.ELIXIR),
            value(ResourceKind.DARK)
        )
    }

    private fun scoreRead(
        fields: MutableMap<String, FieldScore>,
        expected: GroundTruth,
        read: ResultsRead?
    ) {
        score(fields, "result", expected.result, read?.outcome?.name?.lowercase(Locale.US))
        score(fields, "gold", expected.gold, read?.gold)
        score(fields, "elixir", expected.elixir, read?.elixir)
        score(fields, "dark", expected.darkElixir, read?.darkElixir)
        score(fields, "bonus_gold", expected.bonusGold, read?.bonusGold)
        score(fields, "bonus_elixir", expected.bonusElixir, read?.bonusElixir)
        score(fields, "bonus_dark", expected.bonusDarkElixir, read?.bonusDarkElixir)
        score(fields, "balance_gold", expected.balanceGold, read?.balanceGold)
        score(fields, "balance_elixir", expected.balanceElixir, read?.balanceElixir)
        score(fields, "balance_dark", expected.balanceDarkElixir, read?.balanceDarkElixir)
    }

    private fun scoreValues(
        fields: MutableMap<String, FieldScore>,
        expected: GroundTruth,
        values: EngineValues?,
        outcome: ResultsOutcome?
    ) {
        score(fields, "result", expected.result, outcome?.name?.lowercase(Locale.US))
        score(fields, "gold", expected.gold, values?.gold)
        score(fields, "elixir", expected.elixir, values?.elixir)
        score(fields, "dark", expected.darkElixir, values?.dark)
        score(fields, "bonus_gold", expected.bonusGold, values?.bonusGold)
        score(fields, "bonus_elixir", expected.bonusElixir, values?.bonusElixir)
        score(fields, "bonus_dark", expected.bonusDarkElixir, values?.bonusDarkElixir)
        score(fields, "balance_gold", expected.balanceGold, values?.balanceGold)
        score(fields, "balance_elixir", expected.balanceElixir, values?.balanceElixir)
        score(fields, "balance_dark", expected.balanceDarkElixir, values?.balanceDarkElixir)
    }

    private fun compareFields(
        matches: MutableMap<String, Boolean>,
        failures: MutableList<String>,
        expected: GroundTruth,
        read: ResultsRead
    ) {
        fun check(name: String, expectedValue: Any?, actual: Any?) {
            val ok = expectedValue == actual
            matches[name] = ok
            if (!ok) failures += "$name expected=$expectedValue actual=$actual"
        }
        check("result", expected.result, read.outcome.name.lowercase(Locale.US))
        check("gold", expected.gold, read.gold)
        check("elixir", expected.elixir, read.elixir)
        check("dark", expected.darkElixir, read.darkElixir)
        check("bonus_gold", expected.bonusGold, read.bonusGold)
        check("bonus_elixir", expected.bonusElixir, read.bonusElixir)
        check("bonus_dark", expected.bonusDarkElixir, read.bonusDarkElixir)
        check("balance_gold", expected.balanceGold, read.balanceGold)
        check("balance_elixir", expected.balanceElixir, read.balanceElixir)
        check("balance_dark", expected.balanceDarkElixir, read.balanceDarkElixir)
    }

    private fun score(
        fields: MutableMap<String, FieldScore>,
        name: String,
        expected: Any?,
        actual: Any?
    ) {
        val score = fields.getOrPut(name) { FieldScore() }
        score.total++
        if (expected == actual) score.correct++
    }

    private fun feedResult(
        gate: ResultsGate,
        read: ResultsRead,
        onDuplicate: () -> Unit
    ): Int {
        var counted = 0
        gate.observe(read)
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

    private fun crossCheck(
        previous: ResultsRead?,
        current: ResultsRead,
        searchCostGold: Long?
    ): String {
        if (previous == null || searchCostGold == null) return "not_available"
        if (previous.outcome != ResultsOutcome.DEFEAT ||
            current.outcome != ResultsOutcome.VICTORY
        ) {
            return "not_applicable"
        }
        val prevGold = previous.balanceGold ?: return "not_available"
        val prevElixir = previous.balanceElixir ?: return "not_available"
        val prevDark = previous.balanceDarkElixir ?: return "not_available"
        val gold = current.balanceGold ?: return "not_available"
        val elixir = current.balanceElixir ?: return "not_available"
        val dark = current.balanceDarkElixir ?: return "not_available"
        val lootGold = current.gold ?: return "not_available"
        val lootElixir = current.elixir ?: return "not_available"
        val lootDark = current.darkElixir ?: return "not_available"
        val bonusGold = current.bonusGold ?: 0L
        val bonusElixir = current.bonusElixir ?: 0L
        val bonusDark = current.bonusDarkElixir ?: 0L
        val pass = gold - lootGold - bonusGold + searchCostGold == prevGold &&
            elixir - lootElixir - bonusElixir == prevElixir &&
            dark - lootDark - bonusDark == prevDark
        return if (pass) "pass" else "mismatch"
    }

    private fun readSearchCost(page: OcrPage): Long? {
        val anchor = page.lines.firstOrNull {
            normalized(it.text).contains("findamatch")
        } ?: return null
        val anchorBox = anchor.box ?: return null
        return page.lines.mapNotNull { line ->
            val box = line.box ?: return@mapNotNull null
            val mapped = NumberParser.mapNumeric(line.text) ?: return@mapNotNull null
            val digits = mapped.replace(" ", "")
            val value = digits.toLongOrNull()
                ?.takeIf { digits.length in 3..6 }
                ?: return@mapNotNull null
            val distance = box.centerY - anchorBox.bottom
            if (distance < 0 || distance > 160) return@mapNotNull null
            value to distance
        }.minByOrNull { it.second }?.first
    }

    private fun saveFailure(file: File): String? {
        return try {
            val directory = File(context.filesDir, "failed_captures").apply { mkdirs() }
            val existing = directory.listFiles()?.sortedBy { it.lastModified() }.orEmpty()
            if (existing.size >= MAX_FAILED_SCREENSHOTS) {
                existing.take(existing.size - MAX_FAILED_SCREENSHOTS + 1).forEach { it.delete() }
            }
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
        private const val MAX_FAILED_SCREENSHOTS = 20
        private val IMAGE_EXTENSIONS = setOf("jpg", "jpeg", "png", "webp")
    }
}
