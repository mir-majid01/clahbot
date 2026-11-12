package com.clahbot.walls

/**
 * Decides whether a confirm tap actually upgraded a wall (Auto Walls 2.0 PRD §9).
 * The only proof is a fresh re-scan of the upgrade list showing one wall moved
 * between level groups: the upgraded group's "Wall x N" count drops by one, or
 * the next level's count grows by one. Groups are matched by level + currency,
 * never by row position, so a scrolled list still verifies.
 *
 * Everything else stays unverified: an unreadable or unchanged re-scan, a row
 * that merely disappeared (it may just be off-screen), ambiguous duplicate
 * rows, or a resource change that contradicts the confirmed price. A resource
 * drop on its own is never proof, and a tap is never proof.
 */
object UpgradeVerification {

    sealed interface Result {
        data class Verified(val detail: String) : Result
        data class Unverified(val reason: String) : Result
    }

    fun verify(
        before: List<WallCandidate>,
        attempted: WallCandidate,
        after: List<WallCandidate>,
        resourcesBefore: WallCounters.Reading? = null,
        resourcesAfter: WallCounters.Reading? = null
    ): Result {
        if (after.isEmpty()) {
            return Result.Unverified("wall list not readable after the confirm tap")
        }
        val group = after.filter { it.level == attempted.level && it.currency == attempted.currency }
        val nextBefore = before.filter { it.level == attempted.level + 1 && it.currency == attempted.currency }
        val nextAfter = after.filter { it.level == attempted.level + 1 && it.currency == attempted.currency }
        if (group.size > 1 || nextBefore.size > 1 || nextAfter.size > 1) {
            return Result.Unverified("ambiguous wall rows at level ${attempted.level}")
        }

        val now = group.singleOrNull()
        val beforeCount = attempted.count
        val nextBeforeCount = nextBefore.singleOrNull()?.count
        val nextAfterCount = nextAfter.singleOrNull()?.count
        val evidence = mutableListOf<String>()
        if (beforeCount != null && now?.count == beforeCount - 1) {
            evidence += "level ${attempted.level} ${attempted.currency} count $beforeCount->${now.count}"
        }
        if (nextBeforeCount != null && nextAfterCount == nextBeforeCount + 1) {
            evidence += "level ${attempted.level + 1} ${attempted.currency} count $nextBeforeCount->$nextAfterCount"
        }
        if (evidence.isEmpty()) {
            return Result.Unverified(
                when {
                    now == null ->
                        "level ${attempted.level} ${attempted.currency} row not present in the re-scan"
                    beforeCount != null && now.count == beforeCount ->
                        "wall count unchanged at level ${attempted.level}"
                    now.count == null || beforeCount == null ->
                        "wall counts not readable after the confirm tap"
                    else -> "no single-wall change at level ${attempted.level}"
                }
            )
        }

        val spent = spent(resourcesBefore, resourcesAfter, attempted.currency)
        if (spent != null && spent != attempted.price) {
            return Result.Unverified(
                "resource change $spent does not match the confirmed price ${attempted.price}"
            )
        }
        if (spent != null) evidence += "${attempted.currency} spent $spent"
        return Result.Verified(evidence.joinToString("; "))
    }

    private fun spent(
        before: WallCounters.Reading?,
        after: WallCounters.Reading?,
        currency: String
    ): Long? {
        if (before == null || after == null) return null
        val from = when (currency) {
            "gold" -> before.gold
            "elixir" -> before.elixir
            else -> null
        }
        val to = when (currency) {
            "gold" -> after.gold
            "elixir" -> after.elixir
            else -> null
        }
        if (from == null || to == null) return null
        return from - to
    }
}