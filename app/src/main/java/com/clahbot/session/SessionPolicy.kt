package com.clahbot.session

/** Pure 60-second grace rules, so they can be verified off-device. */
object SessionPolicy {

    const val GRACE_MS = 60_000L

    fun nextLeftAt(now: Long, previous: Long?, gameForeground: Boolean): Long? =
        if (gameForeground) null else previous ?: now

    fun shouldClose(
        now: Long,
        leftAt: Long?,
        graceMs: Long = GRACE_MS
    ): Boolean = leftAt != null && now - leftAt >= graceMs
}
