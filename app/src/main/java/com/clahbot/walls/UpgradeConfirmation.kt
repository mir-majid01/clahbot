package com.clahbot.walls

import com.clahbot.ocr.NumberParser
import com.clahbot.ocr.OcrBox
import com.clahbot.ocr.OcrLine
import kotlin.math.abs

/**
 * Validates the wall upgrade confirmation dialog against the candidate the user
 * selected (PRD §3.4, §10). It confirms only when the dialog's title level, the
 * CONFIRM button's **discounted** price and the resource icon all match the
 * expected candidate. Anything premium, short of resources, ambiguous or
 * mismatched is a safe stop — it never chooses a wall itself; it only checks the
 * one already chosen by the user's priority.
 */
object UpgradeConfirmation {

    sealed interface Result {
        data class Confirm(
            val level: Int,
            val price: Long,
            val currency: String,
            /** The dialog's own price line, so the balance read can exclude it. */
            val priceBox: OcrBox? = null
        ) : Result
        data class Refuse(val reason: String) : Result
    }

    private val LEVEL = Regex("level(\\d{1,3})")

    fun validate(
        lines: List<OcrLine>,
        expected: WallCandidate,
        currencyAt: (OcrBox) -> String? = { null }
    ): Result {
        if (lines.isEmpty()) return Result.Refuse("no dialog text")

        // 1. Never purchase: short-of-resources or any premium control → safe stop.
        for (line in lines) {
            if (AutoWallsSafety.isInsufficientResources(line.text)) {
                return Result.Refuse("insufficient resources")
            }
            if (AutoWallsSafety.isPremiumControl(line.text)) {
                return Result.Refuse("premium control: ${line.text.trim()}")
            }
        }

        // 2. It must be a wall upgrade confirmation with a readable level.
        val title = lines.firstOrNull {
            val t = AutoWallsSafety.normalize(it.text)
            t.contains("upgradewall") || (t.contains("upgrade") && t.contains("wall"))
        } ?: return Result.Refuse("not a wall upgrade dialog")
        val level = LEVEL.find(AutoWallsSafety.normalize(title.text))?.groupValues?.get(1)?.toIntOrNull()
            ?: return Result.Refuse("level not readable")
        if (level != expected.level) {
            return Result.Refuse("level mismatch: dialog $level vs ${expected.level}")
        }

        // 3. Discounted CONFIRM price = the numeric line nearest the CONFIRM caption.
        val confirm = lines.firstOrNull { AutoWallsSafety.normalize(it.text).contains("confirm") }
            ?: return Result.Refuse("no confirm button")
        val priceLine = nearestNumberLine(lines, confirm)
            ?: return Result.Refuse("confirm price not readable")
        val price = NumberParser.parseBalance(priceLine.text)
            ?: return Result.Refuse("confirm price not readable")
        if (price != expected.price) {
            return Result.Refuse("cost mismatch: dialog $price vs ${expected.price}")
        }

        // 4. Resource must be the expected gold/elixir (never gem/ring/unknown).
        val currency = priceLine.box?.let(currencyAt) ?: return Result.Refuse("currency not readable")
        if (currency != "gold" && currency != "elixir") {
            return Result.Refuse("non-standard currency: $currency")
        }
        if (currency != expected.currency) {
            return Result.Refuse("currency mismatch: $currency vs ${expected.currency}")
        }

        return Result.Confirm(level, price, currency, priceLine.box)
    }

    /** The numeric line closest to the CONFIRM caption is the button's actual price. */
    private fun nearestNumberLine(lines: List<OcrLine>, confirm: OcrLine): OcrLine? {
        val cb = confirm.box ?: return lines.firstOrNull {
            it !== confirm && it.box != null && NumberParser.parseBalance(it.text) != null
        }
        var best: OcrLine? = null
        var bestDistance = Double.MAX_VALUE
        for (line in lines) {
            if (line === confirm) continue
            val box = line.box ?: continue
            if (NumberParser.parseBalance(line.text) == null) continue
            val dx = abs(box.centerX - cb.centerX).toDouble()
            val dy = abs(box.centerY - cb.centerY).toDouble()
            val distance = dx * dx + dy * dy
            if (distance < bestDistance) {
                bestDistance = distance
                best = line
            }
        }
        return best
    }
}
