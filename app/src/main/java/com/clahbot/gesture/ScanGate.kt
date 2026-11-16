package com.clahbot.gesture

/**
 * Exclusive screen-ownership gate for the read-only wall-list scan.
 *
 * While a scan is running the list may be scrolled by the scanner only: every
 * other automation surface (attack macros, triggers, the recovery watchdog,
 * wall-upgrade taps) must hold its gestures so a competing swipe cannot move
 * the list between the scanner's capture and its reading. Holding this gate
 * never grants the scan a tap path — [GestureAuthority] still gates every tap
 * behind the builder/authorization checks.
 *
 * Pure JVM state so the exclusivity rules are unit-testable.
 */
object ScanGate {

    @Volatile
    private var owner: Long? = null

    /**
     * Acquires the gate for one scan. Returns false when another scan already
     * holds it — a second scan must not start while one is scrolling the list.
     */
    fun begin(scanId: Long): Boolean = synchronized(this) {
        if (owner != null) return false
        owner = scanId
        true
    }

    /** Releases the gate. Safe to call when not held. */
    fun end(scanId: Long) {
        synchronized(this) {
            if (owner == scanId) owner = null
        }
    }

    /** True while a wall-list scan owns the screen. */
    fun isActive(): Boolean = owner != null

    /** True when a specific scan currently owns the screen. */
    fun ownedBy(scanId: Long): Boolean = owner == scanId
}
