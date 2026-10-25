package com.clahbot.ocrlab

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

data class ScreenResult(
    val file: String,
    val expectedResult: String?,
    val detected: Boolean,
    val ignoredReason: String?,
    val read: ResultsRead?,
    val fieldMatches: Map<String, Boolean>,
    val failures: List<String>
)

data class AccuracyReport(
    val images: Int,
    val expectedResults: Int,
    val classificationCorrect: Int,
    val detectedResults: Int,
    val falsePositives: Int,
    val fields: Map<String, FieldScore>,
    val screens: List<ScreenResult>,
    val duplicatesCounted: Int,
    val duplicatesIgnored: Int,
    val notes: List<String>
) {
    fun summary(): String {
        val builder = StringBuilder()
        builder.appendLine("Images: $images")
        builder.appendLine("Screen classification: $classificationCorrect/$images")
        builder.appendLine("Results detected: $detectedResults/$expectedResults (false positives $falsePositives)")
        for ((name, score) in fields) {
            builder.appendLine(
                String.format(
                    Locale.US,
                    "%s: %d/%d (%.1f%%)",
                    name,
                    score.correct,
                    score.total,
                    score.percent
                )
            )
        }
        builder.appendLine("Duplicates: counted $duplicatesCounted, ignored $duplicatesIgnored")
        for (note in notes) builder.appendLine("Note: $note")
        builder.appendLine()
        for (screen in screens) {
            builder.appendLine(
                "${screen.file}: " +
                    if (screen.detected) {
                        "${screen.read?.outcome?.name?.lowercase(Locale.US)} " +
                            "G=${screen.read?.gold ?: "-"} " +
                            "E=${screen.read?.elixir ?: "-"} " +
                            "D=${screen.read?.darkElixir ?: "-"} " +
                            "BG=${screen.read?.bonusGold ?: "-"} " +
                            "BE=${screen.read?.bonusElixir ?: "-"} " +
                            "BD=${screen.read?.bonusDarkElixir ?: "-"}"
                    } else {
                        "ignored (${screen.ignoredReason ?: "no reason"})"
                    }
            )
            if (screen.failures.isNotEmpty()) {
                builder.appendLine("  FAIL: ${screen.failures.joinToString("; ")}")
            }
        }
        return builder.toString()
    }
}

class AccuracyRunner {

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
        val screens = mutableListOf<ScreenResult>()
        var classificationCorrect = 0
        var detectedResults = 0
        var falsePositives = 0
        val gate = ResultsGate(requiredAgreement = 2)
        var duplicatesCounted = 0
        var duplicatesIgnored = 0

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
                    read = null,
                    fieldMatches = emptyMap(),
                    failures = listOf("image could not be decoded")
                )
                continue
            }
            val screen = try {
                val page = ocr.recognize(bitmap)
                ResultsScreenParser(BitmapIconColorSampler(bitmap)).parse(page)
            } finally {
                bitmap.recycle()
            }
            val expected = truth[file.name]
            val read = (screen as? ScreenRead.Results)?.read
            val ignoredReason = (screen as? ScreenRead.Ignored)?.reason

            if (expected == null) {
                if (read == null) {
                    classificationCorrect++
                    gate.observe(null)
                } else {
                    falsePositives++
                }
            } else {
                if (read != null) {
                    classificationCorrect++
                    detectedResults++
                }
            }

            val matches = linkedMapOf<String, Boolean>()
            val failures = mutableListOf<String>()
            if (expected != null) {
                score(fields, matches, failures, "result", expected.result, read?.outcome?.name?.lowercase(Locale.US))
                score(fields, matches, failures, "damage", expected.damagePercent, read?.damagePercent)
                score(fields, matches, failures, "gold", expected.gold, read?.gold)
                score(fields, matches, failures, "elixir", expected.elixir, read?.elixir)
                score(fields, matches, failures, "dark", expected.darkElixir, read?.darkElixir)
                score(fields, matches, failures, "bonus_gold", expected.bonusGold, read?.bonusGold)
                score(fields, matches, failures, "bonus_elixir", expected.bonusElixir, read?.bonusElixir)
                score(fields, matches, failures, "bonus_dark", expected.bonusDarkElixir, read?.bonusDarkElixir)
                score(fields, matches, failures, "balance_gold", expected.balanceGold, read?.balanceGold)
                score(fields, matches, failures, "balance_elixir", expected.balanceElixir, read?.balanceElixir)
                score(fields, matches, failures, "balance_dark", expected.balanceDarkElixir, read?.balanceDarkElixir)
                if (read != null) {
                    duplicatesCounted += feedResult(gate, read) { duplicatesIgnored++ }
                }
            }
            screens += ScreenResult(
                file = file.name,
                expectedResult = expected?.result,
                detected = read != null,
                ignoredReason = ignoredReason,
                read = read,
                fieldMatches = matches,
                failures = failures
            )
        }

        return AccuracyReport(
            images = images.size,
            expectedResults = truth.size,
            classificationCorrect = classificationCorrect,
            detectedResults = detectedResults,
            falsePositives = falsePositives,
            fields = fields,
            screens = screens,
            duplicatesCounted = duplicatesCounted,
            duplicatesIgnored = duplicatesIgnored,
            notes = listOf(
                "Stars are not parsed by this spike; stored as unknown for now.",
                "Balances are a soft check; dim text can be unreadable.",
                "Duplicate cycle: each results screen is fed twice (count) then once more (duplicate)."
            )
        )
    }

    private fun feedResult(
        gate: ResultsGate,
        read: ResultsRead,
        onDuplicate: () -> Unit
    ): Int {
        var counted = 0
        gate.observe(read) // first observation: stabilizing
        when (gate.observe(read)) {
            is GateResult.Counted -> counted++
            is GateResult.Duplicate -> onDuplicate()
            else -> Unit
        }
        when (gate.observe(read)) {
            is GateResult.Duplicate -> onDuplicate()
            else -> Unit
        }
        gate.observe(null) // screen disappeared; re-arm
        return counted
    }

    private fun score(
        fields: MutableMap<String, FieldScore>,
        matches: MutableMap<String, Boolean>,
        failures: MutableList<String>,
        name: String,
        expected: Any?,
        actual: Any?
    ) {
        val score = fields.getOrPut(name) { FieldScore() }
        score.total++
        val ok = expected == actual
        if (ok) score.correct++
        matches[name] = ok
        if (!ok) failures += "$name expected=$expected actual=$actual"
    }

    companion object {
        private val IMAGE_EXTENSIONS = setOf("jpg", "jpeg", "png", "webp")
    }
}
