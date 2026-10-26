package com.clahbot.ocrlab

import android.graphics.Bitmap
import android.graphics.Color
import java.util.Locale
import kotlin.math.max
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Feeds one row to every reader and saves the exact bitmap each reader saw.
 * The full-page result is one reader; the four crop readers are independent
 * votes. A value survives only through [RowConsensus].
 */
class RowCropReader(
    private val mlKit: OcrEngine,
    private val tesseract: TesseractRowEngine?,
    private val rapidOcr: RapidOcrRowEngine?,
    private val templates: DigitTemplateEngine?,
    private val cropSink: CropSink?
) {

    suspend fun read(
        bitmap: Bitmap,
        row: RowCandidate,
        index: Int,
        fullPageCrop: String?
    ): RowReading {
        val tag = "${row.role.name.lowercase(Locale.US)}_${index + 1}"
        val source = cropSource(bitmap, row.box)
        cropSink?.save("${tag}_source", source)

        val templateRead = try {
            templates?.let { withContext(Dispatchers.Default) { it.read(source) } }
        } catch (_: Throwable) {
            null
        }
        val templateCrop = templateRead?.let { cropSink?.save("${tag}_template", it.binary) }

        val tight = tighten(source, templateRead?.bounds)
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
        val mlKitCrop = cropSink?.save("${tag}_mlkit", scaled)
        val mlResult = runCatching { bestLine(mlKit.recognize(scaled)) }
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
        val tesseractCrop = cropSink?.save("${tag}_tesseract", inverted)
        if (tesseract != null) {
            val tessText = try {
                withContext(Dispatchers.IO) { tesseract.recognize(inverted) }
            } catch (_: Throwable) {
                ""
            }
            val tessCandidates = candidates(row.role, tessText)
            reads += EngineRowRead(
                reader = Readers.TESSERACT_CROP,
                text = tessText,
                candidates = tessCandidates,
                chosen = single(tessCandidates),
                crop = tesseractCrop
            )
        }

        if (rapidOcr != null) {
            val rapid = try {
                rapidOcr.recognize(tight)
            } catch (throwable: Throwable) {
                null
            }
            if (rapid != null) {
                val rapidCrop = cropSink?.save("${tag}_rapidocr", rapid.input)
                val rapidCandidates = candidates(row.role, rapid.text)
                reads += EngineRowRead(
                    reader = Readers.RAPIDOCR_CROP,
                    text = rapid.text,
                    candidates = rapidCandidates,
                    chosen = single(rapidCandidates),
                    crop = rapidCrop
                )
                rapid.input.recycle()
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
        }

        if (templateRead != null) {
            val values = templateRead.value?.let { listOf(it) } ?: emptyList()
            reads += EngineRowRead(
                reader = Readers.TEMPLATE_CROP,
                text = templateRead.text,
                candidates = values,
                chosen = templateRead.value,
                crop = templateCrop
            )
        } else if (templates != null) {
            reads += EngineRowRead(
                reader = Readers.TEMPLATE_CROP,
                text = "",
                candidates = emptyList(),
                chosen = null,
                crop = null,
                error = "digit templates failed"
            )
        }

        val result = RowConsensus.resolve(reads)
        val reading = RowReading(
            role = row.role,
            index = index,
            resource = row.resource,
            box = row.box,
            reads = reads,
            combined = result.value,
            sources = result.sources,
            status = result.status
        )

        templateRead?.binary?.recycle()
        if (tight !== source) tight.recycle()
        source.recycle()
        scaled.recycle()
        inverted.recycle()
        return reading
    }

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
        val leftPad = max(10, box.height * 3 / 2)
        val rightPad = max(4, box.height / 4)
        val verticalPad = max(3, box.height / 3)
        val left = (box.left - leftPad).coerceIn(0, bitmap.width - 1)
        val top = (box.top - verticalPad).coerceIn(0, bitmap.height - 1)
        val right = (box.right + rightPad).coerceIn(left + 1, bitmap.width)
        val bottom = (box.bottom + verticalPad).coerceIn(top + 1, bitmap.height)
        return Bitmap.createBitmap(bitmap, left, top, right - left, bottom - top)
    }

    private fun tighten(source: Bitmap, bounds: OcrBox?): Bitmap {
        if (bounds == null) return source
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
