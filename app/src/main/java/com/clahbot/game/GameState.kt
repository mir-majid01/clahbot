package com.clahbot.game

import com.clahbot.ocr.OcrBox

/** Coarse game screen classes the watchdog and the wall upgrader act on. */
enum class GameState {
    HOME,
    ATTACK_MENU,
    SEARCHING,
    BATTLE,
    RESULTS,
    POPUP,
    LOADING,
    NOT_CLASH,
    UNKNOWN
}

/** Popup families; the recovery rules pick an action per family. */
enum class PopupKind {
    CONNECTION_LOST,
    SURRENDER_CONFIRM,
    EXIT_CONFIRM,
    SHOP_OR_OFFER,
    GEM_SPEND,
    RATE_OR_SEASON,
    GENERIC
}

/** A recognised text line in frame fractions (0..1 of the captured frame). */
data class TextLine(
    val text: String,
    val left: Float,
    val top: Float,
    val right: Float,
    val bottom: Float
) {
    val centerX: Float get() = (left + right) / 2f
    val centerY: Float get() = (top + bottom) / 2f
}

/** A detected screen element in captured-frame pixels. */
data class ScreenBox(val left: Int, val top: Int, val right: Int, val bottom: Int) {
    val width: Int get() = (right - left).coerceAtLeast(1)
    val height: Int get() = (bottom - top).coerceAtLeast(1)
    val centerX: Int get() = (left + right) / 2
    val centerY: Int get() = (top + bottom) / 2

    fun intersects(other: ScreenBox): Boolean =
        left < other.right && other.left < right && top < other.bottom && other.top < bottom

    fun contains(x: Int, y: Int): Boolean = x in left until right && y in top until bottom

    fun expanded(marginX: Int, marginY: Int, frameW: Int, frameH: Int): ScreenBox = ScreenBox(
        left = (left - marginX).coerceAtLeast(0),
        top = (top - marginY).coerceAtLeast(0),
        right = (right + marginX).coerceAtMost(frameW),
        bottom = (bottom + marginY).coerceAtMost(frameH)
    )

    companion object {
        fun fromOcr(box: OcrBox, frameW: Int, frameH: Int): ScreenBox = ScreenBox(
            left = box.left.coerceIn(0, frameW),
            top = box.top.coerceIn(0, frameH),
            right = box.right.coerceIn(0, frameW),
            bottom = box.bottom.coerceIn(0, frameH)
        )
    }
}

/** One classification pass over a captured game frame. */
data class ScreenObservation(
    val state: GameState,
    val confidence: Float,
    val popupKind: PopupKind? = null,
    /** Red square X close button, frame pixels. */
    val closeButton: ScreenBox? = null,
    /** Wide green Confirm/Okay button, frame pixels. */
    val confirmButton: ScreenBox? = null,
    /** Dismiss targets in priority order: red X first, then caption buttons. */
    val safeTargets: List<ScreenBox> = emptyList(),
    /** Boxes that must never be tapped (gem prices, Buy, Surrender, Quit...). */
    val forbidden: List<ScreenBox> = emptyList(),
    val lines: List<TextLine> = emptyList(),
    /** Frame pixel size, so callers can map fractions back for taps. */
    val frameWidth: Int = 0,
    val frameHeight: Int = 0,
    val hash: String = "",
    val meanValue: Float = 0f,
    val meanSaturation: Float = 0f
)
