package com.clahbot.walls

import java.util.Locale

/**
 * Part 2 — the display-only decision for the scan panel.
 *
 * This is an explanation, not an execution path: it reads a finished
 * [AutoWallsScanReport] and the user's reserves and says what the pipeline
 * WOULD decide. It cannot tap, scroll or start the machine — there is no device
 * and no gesture type in this file.
 *
 * The answer is blocked unless every precondition the runtime itself enforces
 * is met: a captured frame, one selected wall, a readable and live balance, a
 * known free regular builder, and enough balance left after the price to keep
 * the reserve. Failing open here would advertise an upgrade the runtime would
 * refuse, so every unknown is a block with its own reason.
 */
data class DryRunDecision(
    val wouldUpgrade: Boolean,
    val text: String
) {
    val isBlocked: Boolean get() = !wouldUpgrade
}

object AutoWallsDryRun {

    /** The exact text shown by the panel for this report and these reserves. */
    fun decide(report: AutoWallsScanReport, reserves: WallTriggerConfig): DryRunDecision {
        if (!report.captured) {
            return blocked(report.reason ?: "no frame captured")
        }
        val wall = report.selected
            ?: return blocked(report.reason ?: "no eligible wall")

        // The builder gate comes first because the runtime stops there too: an
        // upgrade with no free regular builder would be refused before any tap.
        val free = report.builderFree
            ?: return blocked("builder availability unknown")
        val total = report.builderTotal
        if (free <= 0) {
            val count = if (total != null) " ($free/$total free)" else ""
            return blocked("no free regular builder$count")
        }

        if (wall.price <= 0L) return blocked("wall price not readable")
        val currency = wall.currency.lowercase(Locale.US)
        val balance = when (currency) {
            "gold" -> report.goldBalance
            "elixir" -> report.elixirBalance
            // Dark elixir is never a wall price the selection accepts, and an
            // unknown currency must not be treated as gold.
            else -> return blocked("cannot pay with ${currency.ifBlank { "an unknown currency" }}")
        }
        val stale = if (currency == "gold") report.goldStale else report.elixirStale
        val reserve = if (currency == "gold") {
            reserves.goldReserve
        } else {
            reserves.elixirReserve
        }
        if (balance == null) return blocked("$currency balance not readable")
        if (stale) return blocked("$currency balance is stale (not read on this frame)")

        val after = balance - wall.price
        if (after < reserve) {
            return blocked(
                "$currency reserve would be broken: ${amount(balance)} - " +
                    "${amount(wall.price)} = ${amount(after)} < ${amount(reserve)}"
            )
        }
        return DryRunDecision(
            wouldUpgrade = true,
            text = "would upgrade ${wallLabel(wall)} " +
                "(level ${wall.level}, ${amount(wall.price)} $currency)"
        )
    }

    /** `Wall x N` when the list label was read, else an honest placeholder. */
    fun wallLabel(wall: WallCandidate): String =
        wall.count?.let { "Wall x $it" } ?: "Wall (count unreadable)"

    /**
     * The amount in a user-typed reserve field. Spaces and commas are grouping
     * noise (the game shows "1 000 000"), so only the digits are kept. Null
     * means the field holds no digit at all.
     */
    fun parseAmount(text: String): Long? {
        val digits = text.filter { it.isDigit() }
        if (digits.isEmpty()) return null
        return digits.toLongOrNull()
    }

    /** The text a reserve field shows for a stored value. */
    fun formatAmount(value: Long): String = if (value <= 0L) "" else value.toString()

    private fun blocked(reason: String) = DryRunDecision(false, "blocked: $reason")

    private fun amount(value: Long): String = String.format(Locale.US, "%,d", value)
}
