package com.clahbot.ocrlab

import android.graphics.Rect

data class OcrBox(
    val left: Int,
    val top: Int,
    val right: Int,
    val bottom: Int
) {
    val width: Int get() = (right - left).coerceAtLeast(1)
    val height: Int get() = (bottom - top).coerceAtLeast(1)
    val centerX: Int get() = (left + right) / 2
    val centerY: Int get() = (top + bottom) / 2

    fun format(): String = "($left,$top)-($right,$bottom)"
}

data class OcrElement(
    val text: String,
    val box: OcrBox?
)

data class OcrLine(
    val text: String,
    val box: OcrBox?,
    val elements: List<OcrElement>
)

data class OcrPage(
    val width: Int,
    val height: Int,
    val lines: List<OcrLine>
)

data class EngineRowRead(
    val reader: String,
    val text: String,
    val candidates: List<Long>,
    val chosen: Long?,
    val crop: String? = null,
    val error: String? = null
) {
    val hasValue: Boolean get() = error == null && candidates.isNotEmpty()
}

data class RowReading(
    val role: RowRole,
    val index: Int,
    val resource: ResourceKind?,
    val box: OcrBox,
    val reads: List<EngineRowRead>,
    val combined: Long?,
    val sources: List<String>,
    val status: String,
    val expectedFromMath: Long? = null
) {
    fun withResult(expected: Long?, result: ConsensusResult): RowReading = copy(
        combined = result.value,
        sources = result.sources,
        status = result.status,
        expectedFromMath = expected
    )
}

data class ConsensusResult(
    val value: Long?,
    val sources: List<String>,
    val status: String
)

object Readers {
    const val MLKIT_FULL = "mlkit_full"
    const val MLKIT_CROP = "mlkit_crop"
    const val TESSERACT_CROP = "tesseract_crop"
    const val RAPIDOCR_CROP = "rapidocr_crop"
    const val TEMPLATE_CROP = "template_crop"
    const val CONSENSUS = "consensus"
    const val BALANCE_MATH = "balance_math"

    val ALL_CROPS = listOf(MLKIT_CROP, TESSERACT_CROP, RAPIDOCR_CROP, TEMPLATE_CROP)
    val ALL = listOf(MLKIT_FULL) + ALL_CROPS
}

fun Rect.toOcrBox(): OcrBox =
    OcrBox(left = left, top = top, right = right, bottom = bottom)
