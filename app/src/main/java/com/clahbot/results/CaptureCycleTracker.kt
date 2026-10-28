package com.clahbot.results

enum class PollAction {
    NONE,
    CAPTURE,
    WAIT_FOR_CLEAR,
    ALREADY_RECORDED
}

/**
 * Allows one record per visible result screen. A screen that was already
 * recorded must disappear before the next result can be captured, so a loop
 * can record every attack without recording the same screen twice.
 */
class CaptureCycleTracker {

    private var lastRecordedHash: String? = null
    private var screenClear = true
    private var clearStreak = 0

    fun onPoll(results: Boolean, hash: String?): PollAction {
        if (!results) {
            // A result screen's animations can make the anchors disappear for
            // a poll. If the frame still looks like the recorded screen, keep
            // waiting instead of treating it as cleared.
            val stillSameScreen = !screenClear && isSameScreen(hash)
            if (!screenClear && !stillSameScreen) {
                clearStreak++
                if (clearStreak >= CLEAR_POLLS_REQUIRED) {
                    screenClear = true
                    clearStreak = 0
                }
            }
            return PollAction.NONE
        }
        clearStreak = 0
        if (!screenClear) {
            return if (hash != null && hash == lastRecordedHash) {
                PollAction.ALREADY_RECORDED
            } else {
                PollAction.WAIT_FOR_CLEAR
            }
        }
        return PollAction.CAPTURE
    }

    fun onRecorded(hash: String?) {
        lastRecordedHash = hash
        screenClear = false
        clearStreak = 0
    }

    private fun isSameScreen(hash: String?): Boolean {
        val distance = ScreenHash.distance(hash, lastRecordedHash) ?: return false
        return distance <= SIMILAR_SCREEN_BITS
    }

    private companion object {
        // Result-screen animations can make one detection poll miss the
        // anchors. A single miss must not count as "the screen cleared".
        const val CLEAR_POLLS_REQUIRED = 3

        // Same-screen animation frames differed by at most 12 bits in the
        // observed captures; a different battle scene differs much more.
        const val SIMILAR_SCREEN_BITS = 16
    }
}
