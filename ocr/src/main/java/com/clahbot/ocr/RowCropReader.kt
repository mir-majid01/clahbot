package com.clahbot.ocr

import android.graphics.Bitmap
import android.graphics.Color
import java.util.Locale
import kotlin.math.max
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Feeds one row to the readers and saves the exact bitmap each reader saw.
 *
 * The full-page and crop ML Kit plus Tesseract are the cheap pass. RapidOCR
 * runs only when the cheap readers cannot agree; digit templates run only when
 * the cheap+Rapid pass still cannot agree. A template classification is never
 * used in consensus or scoring when the screen was part of its training set.
 */
class RowCropReader(
    private val mlKit: OcrEngine,
    private val tesseract: TesseractRowEngine?,
    private val rapidOcr: RapidOcrRowEngine?,
    private val templates: DigitTemplateEngine?,
    private val cropSink: CropSink?,
    private val strategy: ReaderStrategy = ReaderStrategy.CHEAP_FIRST,
    private val runTemplateForTiming: Boolean = strategy == ReaderStrategy.ALL
) {

    private class Timers {
        private val values = linkedMapOf<String, Long>()
        fun add(key: String, millis: Long) {
            values[key] = (values[key] ?: 0L) + millis
        }
        fun snapshot(): Map<String, Long> = values.toMap()
    }

    suspend fun read(
        bitmap: Bitmap,
        row: RowCandidate,
        index: Int,
        fullPageCrop: String?,
        templateVoteEligible: Boolean = true
    ): RowReading {
        val timers = Timers()
        val tag = "${row.role.name.lowercase(Locale.US)}_${index + 1}"
        val source = cropSource(bitmap, row.box)
        save("${tag}_source", source, timers)

        val segmentStart = System.nanoTime()
        val segmentation = try {
            templates?.let { withContext(Dispatchers.Default) { it.segment(source) } }
        } catch (_: Throwable) {
            null
        }
        timers.add(TimingKeys.SEGMENT, elapsed(segmentStart))
        val templateCrop = segmentation?.let { save("${tag}_template", it.binary, timers) }

        // Crop readers see the whole row slot. Tightening to the template
        // segmentation can keep only the larger resource icon and cut the
        // digits out, which made ML Kit's crop reader return nothing.
        val tight = source
        val reads = mutableListOf<EngineRowRead>()

        val fullCandidates = candidates(row.role, row.rawText)
        reads += EngineRowRead(
            reader = Readers.MLKIT_FULL,
            text = row.rawText,
            candidates = fullCandidates,
            chosen = single(fullCandidates),
            crop = fullPageCrop
        )

        val scaled = Bitmap.createScaledBitmap(tight, tight.width * 3, tight.height * 3, true)
        val mlKitCrop = save("${tag}_mlkit", scaled, timers)
        val mlStart = System.nanoTime()
        val mlResult = runCatching { bestLine(mlKit.recognize(scaled)) }
        timers.add(TimingKeys.MLKIT_CROP, elapsed(mlStart))
        if (mlResult.isSuccess) {
            val text = mlResult.getOrNull().orEmpty()
            val mlCandidates = candidates(row.role, text)
            reads += EngineRowRead(
                reader = Readers.MLKIT_CROP,
                text = text,
                candidates = mlCandidates,
                chosen = single(mlCandidates),
                crop = mlKitCrop
            )
        } else {
            reads += EngineRowRead(
                reader = Readers.MLKIT_CROP,
                text = "",
                candidates = emptyList(),
                chosen = null,
                crop = null,
                error = "ML Kit crop failed: ${mlResult.exceptionOrNull()?.message}"
            )
        }

        val inverted = invertToGrayscale(scaled)
        val tesseractCrop = save("${tag}_tesseract", inverted, timers)
        if (tesseract != null) {
            val tessStart = System.nanoTime()
            val tessText = try {
                withContext(Dispatchers.IO) { tesseract.recognize(inverted) }
            } catch (_: Throwable) {
                ""
            }
            timers.add(TimingKeys.TESSERACT_CROP, elapsed(tessStart))
            val tessCandidates = candidates(row.role, tessText)
            reads += EngineRowRead(
                reader = Readers.TESSERACT_CROP,
                text = tessText,
                candidates = tessCandidates,
                chosen = single(tessCandidates),
                crop = tesseractCrop
            )
        }

        var result = resolve(reads, timers)

        if (rapidOcr != null) {
            val runRapid = strategy == ReaderStrategy.ALL || result.value == null
            if (runRapid) {
                val rapidStart = System.nanoTime()
                val rapidSource = segmentation?.bounds
                    ?.let { tighten(source, it) }
                    ?: source
                val rapid = try {
                    rapidOcr.recognize(rapidSource)
                } catch (_: Throwable) {
                    null
                }
                if (rapidSource !== source) rapidSource.recycle()
                timers.add(TimingKeys.RAPIDOCR_CROP, elapsed(rapidStart))
                if (rapid != null) {
                    val rapidCrop = save("${tag}_rapidocr", rapid.input, timers)
                    val rapidCandidates = candidates(row.role, rapid.text)
                    reads += EngineRowRead(
                        reader = Readers.RAPIDOCR_CROP,
                        text = rapid.text,
                        candidates = rapidCandidates,
                        chosen = single(rapidCandidates),
                        crop = rapidCrop
                    )
                    rapid.input.recycle()
                    result = resolve(reads, timers)
                } else {
                    reads += EngineRowRead(
                        reader = Readers.RAPIDOCR_CROP,
                        text = "",
                        candidates = emptyList(),
                        chosen = null,
                        crop = null,
                        error = "RapidOCR failed"
                    )
                }
            } else {
                reads += EngineRowRead(
                    reader = Readers.RAPIDOCR_CROP,
                    text = "",
                    candidates = emptyList(),
                    chosen = null,
                    crop = null,
                    skipped = true,
                    note = "skipped: cheap readers agreed"
                )
            }
        }

        val emptyHint = row.rawText.isEmpty()
        val runTemplate = templates != null && segmentation != null && (
            if (!templateVoteEligible) {
                runTemplateForTiming
            } else {
                emptyHint || strategy == ReaderStrategy.ALL || result.value == null
            }
            )
        if (templates != null && segmentation != null && runTemplate) {
            val templateStart = System.nanoTime()
            val templateRead = try {
                withContext(Dispatchers.Default) { templates.classify(segmentation) }
            } catch (_: Throwable) {
                null
            }
            timers.add(TimingKeys.TEMPLATE_CROP, elapsed(templateStart))
            if (templateRead != null) {
                val values = templateRead.value?.let { listOf(it) } ?: emptyList()
                reads += EngineRowRead(
                    reader = Readers.TEMPLATE_CROP,
                    text = templateRead.text,
                    candidates = values,
                    chosen = templateRead.value,
                    crop = templateCrop,
                    excluded = !templateVoteEligible,
                    note = if (!templateVoteEligible) {
                        "excluded: template training image"
                    } else {
                        null
                    }
                )
                if (templateVoteEligible) {
                    result = resolve(reads, timers)
                }
            } else {
                reads += EngineRowRead(
                    reader = Readers.TEMPLATE_CROP,
                    text = "",
                    candidates = emptyList(),
                    chosen = null,
                    crop = templateCrop,
                    error = "digit templates failed"
                )
            }
        } else if (templates != null) {
            if (segmentation == null) {
                reads += EngineRowRead(
                    reader = Readers.TEMPLATE_CROP,
                    text = "",
                    candidates = emptyList(),
                    chosen = null,
                    crop = null,
                    error = "digit-template segmentation failed"
                )
            } else {
                reads += EngineRowRead(
                    reader = Readers.TEMPLATE_CROP,
                    text = "",
                    candidates = emptyList(),
                    chosen = null,
                    crop = templateCrop,
                    skipped = true,
                    note = if (!templateVoteEligible) {
                        "excluded: template training image"
                    } else {
                        "skipped: cheap readers agreed"
                    }
                )
            }
        }

        // Without a full-page number line, only a confirmed single zero may
        // be recorded. This catches real zero-loot results without ever
        // turning a fragment of a larger number into a value.
        val finalResult = if (emptyHint && templateVoteEligible) {
            val templateRead = reads.firstOrNull { it.reader == Readers.TEMPLATE_CROP }
            val ocrZero = reads.any {
                it.reader != Readers.TEMPLATE_CROP && it.eligibleForVote &&
                    it.candidates == listOf(0L)
            }
            val templateZero = templateRead?.text?.trim() == "0" &&
                templateRead.candidates == listOf(0L)
            if (result.value == 0L && ocrZero && templateZero) {
                ConsensusResult(0L, result.sources + Readers.TEMPLATE_CROP, "zero_confirmed")
            } else {
                ConsensusResult(null, result.sources, "zero_unverified")
            }
        } else {
            result
        }

        val reading = RowReading(
            role = row.role,
            index = index,
            resource = row.resource,
            box = row.box,
            reads = reads,
            combined = finalResult.value,
            sources = finalResult.sources,
            status = finalResult.status,
            timingsMs = timers.snapshot()
        )

        segmentation?.binary?.recycle()
        if (tight !== source) tight.recycle()
        source.recycle()
        scaled.recycle()
        inverted.recycle()
        return reading
    }

    private fun resolve(reads: List<EngineRowRead>, timers: Timers): ConsensusResult {
        val start = System.nanoTime()
        val result = RowConsensus.resolve(reads)
        timers.add(TimingKeys.CONSENSUS, elapsed(start))
        return result
    }

    private fun save(tag: String, bitmap: Bitmap, timers: Timers): String? {
        if (cropSink == null) return null
        val start = System.nanoTime()
        val path = cropSink.save(tag, bitmap)
        timers.add(TimingKeys.CROP_SAVE, elapsed(start))
        return path
    }

    private fun elapsed(startNanos: Long): Long =
        (System.nanoTime() - startNanos) / 1_000_000L

    private fun candidates(role: RowRole, text: String): List<Long> {
        val parsed = when (role) {
            RowRole.BONUS -> NumberParser.bonusCandidates(text)
            else -> NumberParser.lootCandidates(text)
        }
        return parsed.map { it.value }.distinct()
    }

    private fun single(candidates: List<Long>): Long? =
        candidates.singleOrNull()

    private fun bestLine(page: OcrPage): String {
        val candidates = page.lines
            .map { it.text.trim() }
            .filter { NumberParser.lootCandidates(it).isNotEmpty() }
        return candidates.maxByOrNull { line -> line.count { it.isDigit() } }
            ?: page.lines.joinToString(" ") { it.text }.trim()
    }

    private fun cropSource(bitmap: Bitmap, box: OcrBox): Bitmap {
        val leftPad = max(8, minOf(box.height / 2, box.width / 3))
        val rightPad = max(4, minOf(box.height / 4, box.width / 4))
        val verticalPad = max(3, box.height / 8)
        val left = (box.left - leftPad).coerceIn(0, bitmap.width - 1)
        val top = (box.top - verticalPad).coerceIn(0, bitmap.height - 1)
        val right = (box.right + rightPad).coerceIn(left + 1, bitmap.width)
        val bottom = (box.bottom + verticalPad).coerceIn(top + 1, bitmap.height)
        return Bitmap.createBitmap(bitmap, left, top, right - left, bottom - top)
    }

    private fun tighten(source: Bitmap, bounds: OcrBox): Bitmap {
        val pad = max(3, bounds.height / 5)
        val left = (bounds.left - pad).coerceIn(0, source.width - 1)
        val top = (bounds.top - pad).coerceIn(0, source.height - 1)
        val right = (bounds.right + pad).coerceIn(left + 1, source.width)
        val bottom = (bounds.bottom + pad).coerceIn(top + 1, source.height)
        if (left == 0 && top == 0 && right == source.width && bottom == source.height) {
            return source
        }
        return Bitmap.createBitmap(source, left, top, right - left, bottom - top)
    }

    /** White game text becomes dark text on a light background for Tesseract. */
    private fun invertToGrayscale(bitmap: Bitmap): Bitmap {
        val width = bitmap.width
        val height = bitmap.height
        val pixels = IntArray(width * height)
        bitmap.getPixels(pixels, 0, width, 0, 0, width, height)
        val output = IntArray(pixels.size)
        for (index in pixels.indices) {
            val pixel = pixels[index]
            val red = Color.red(pixel)
            val green = Color.green(pixel)
            val blue = Color.blue(pixel)
            val luminance = 0.299f * red + 0.587f * green + 0.114f * blue
            val contrasted = ((luminance - 128f) * 1.6f + 128f).coerceIn(0f, 255f)
            val inverted = (255f - contrasted).toInt()
            output[index] = Color.rgb(inverted, inverted, inverted)
        }
        val result = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        result.setPixels(output, 0, width, 0, 0, width, height)
        return result
    }
}
