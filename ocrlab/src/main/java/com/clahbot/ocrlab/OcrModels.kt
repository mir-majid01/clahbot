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

fun Rect.toOcrBox(): OcrBox =
    OcrBox(left = left, top = top, right = right, bottom = bottom)
