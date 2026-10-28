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

    fun onPoll(results: Boolean, hash: String?): PollAction {
        if (!results) {
            screenClear = true
            return PollAction.NONE
        }
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
    }
}
