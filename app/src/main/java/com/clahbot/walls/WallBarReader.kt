package com.clahbot.walls

import com.clahbot.game.GameScreenEngine
import com.clahbot.game.ScreenBox
import com.clahbot.ocr.IconColorSampler
import com.clahbot.ocr.NumberParser
import com.clahbot.ocr.OcrPage
import com.clahbot.ocr.ResourceKind

/**
 * Runtime finder for the wall-selection bottom bar: the "Wall (Level X)"
 * caption, the Upgrade / Upgrade More / +10 / +1 buttons and the resource
 * price. Everything is matched by caption text in the bottom band plus icon
 * colour — never by fixed positions. A price whose icon is not gold or elixir
 * (the blue Wall Ring) is never reported, so rings can never be bought.
 */
class WallBarReader(
    private val page: OcrPage,
    private val frameWidth: Int,
    private val frameHeight: Int,
    private val sampler: IconColorSampler
) {

    data class Selection(
        val level: Int,
        val box: ScreenBox
    )

    data class Price(
        val amount: Long,
        val resource: ResourceKind,
        val box: ScreenBox
    )

    private val barLines = page.lines
        .mapNotNull { line -> line.box?.let { line.text to it } }
        .filter { (_, box) -> box.top > frameHeight * BAR_TOP_FRACTION }

    /** "Wall (Level 11)" -> level 11. */
    fun selection(): Selection? {
        for ((text, box) in barLines) {
            val normalized = GameScreenEngine.normalize(text)
            if (!normalized.contains("wall") || !normalized.contains("level")) continue
            val level = Regex("level(\\d+)").find(normalized)?.groupValues?.get(1)
                ?.toIntOrNull() ?: continue
            return Selection(level, boxFrom(box))
        }
        return null
    }

    fun upgradeMoreButton(): ScreenBox? = captionBox { it.contains("upgrademore") }

    /** Exact "Upgrade" caption; Upgrade More is matched separately. */
    fun upgradeButton(): ScreenBox? = captionBox { it == "upgrade" }

    fun plusButton(count: Int): ScreenBox? =
        captionBox { it == count.toString() }

    /** The multi-select chip "x 3" -> 3. */
    fun countLabel(): Int? {
        for ((text, box) in barLines) {
            val normalized = GameScreenEngine.normalize(text)
            val match = Regex("^x(\\d+)$").find(normalized) ?: continue
            if (box.width > frameWidth * 0.12f) continue
            return match.groupValues[1].toIntOrNull()
        }
        return null
    }

    /**
     * The price shown on the chosen resource button. Only gold and elixir
     * icons are accepted; a blue Wall Ring price is never returned.
     */
    fun price(resource: WallResource): Price? {
        val wanted = when (resource) {
            WallResource.GOLD -> ResourceKind.GOLD
            WallResource.ELIXIR -> ResourceKind.ELIXIR
        }
        var best: Price? = null
        for ((text, box) in barLines) {
            val normalized = GameScreenEngine.normalize(text)
            val amount = NumberParser.parseBalance(normalized) ?: continue
            if (normalized.contains("level")) continue
            val frameBox = boxFrom(box)
            val icon = sampler.classify(
                com.clahbot.ocr.OcrBox(box.left, box.top, box.right, box.bottom)
            ) ?: continue
            if (icon != wanted) continue
            val candidate = Price(amount, icon, frameBox)
            if (best == null || frameBox.width > best.box.width) best = candidate
        }
        return best
    }

    private fun captionBox(predicate: (String) -> Boolean): ScreenBox? {
        var best: ScreenBox? = null
        for ((text, box) in barLines) {
            val normalized = GameScreenEngine.normalize(text)
            if (!predicate(normalized)) continue
            val frameBox = boxFrom(box).expanded(
                frameWidth / 56,
                frameHeight / 36,
                frameWidth,
                frameHeight
            )
            if (best == null || frameBox.width > best.width) best = frameBox
        }
        return best
    }

    private fun boxFrom(box: com.clahbot.ocr.OcrBox): ScreenBox = ScreenBox(
        left = box.left.coerceIn(0, frameWidth),
        top = box.top.coerceIn(0, frameHeight),
        right = box.right.coerceIn(0, frameWidth),
        bottom = box.bottom.coerceIn(0, frameHeight)
    )

    companion object {
        /** Bottom band that holds the building selection bar. */
        const val BAR_TOP_FRACTION = 0.80f
    }
}
