package com.clahbot.walls

import com.clahbot.game.ScreenBox
import com.clahbot.game.TextLine
import com.clahbot.ocr.IconColorSampler
import com.clahbot.ocr.NumberParser
import com.clahbot.ocr.OcrBox
import com.clahbot.ocr.ResourceKind

/**
 * Picks the price the player actually pays out of the confirm dialog.
 *
 * The dialog shows the old price struck through in small type and the price
 * actually paid in large type, so the tallest digit-only line wins. The icon
 * next to the amount decides gold vs elixir; a blue Wall Ring or gem button
 * never classifies as either, which is why a ring price can never be returned.
 */
object WallDialogParser {

    data class Price(val amount: Long, val box: ScreenBox, val icon: ResourceKind)

    fun largePrice(
        lines: List<TextLine>,
        frameW: Int,
        frameH: Int,
        resource: WallResource,
        sampler: IconColorSampler
    ): Price? {
        if (frameW <= 0 || frameH <= 0) return null
        val wanted = when (resource) {
            WallResource.GOLD -> ResourceKind.GOLD
            WallResource.ELIXIR -> ResourceKind.ELIXIR
        }
        var best: Price? = null
        for (line in lines) {
            val inDialog = line.left in 0.12f..0.88f && line.top in 0.15f..0.85f
            if (!inDialog) continue
            if (line.text.any { it.isLetter() }) continue
            val amount = NumberParser.parseBalance(line.text) ?: continue
            val box = ScreenBox(
                left = (line.left * frameW).toInt(),
                top = (line.top * frameH).toInt(),
                right = (line.right * frameW).toInt(),
                bottom = (line.bottom * frameH).toInt()
            )
            val icon = sampler.classify(OcrBox(box.left, box.top, box.right, box.bottom))
            if (icon != wanted) continue
            if (best == null || box.height > best.box.height) {
                best = Price(amount, box, icon)
            }
        }
        return best
    }
}
