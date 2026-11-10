package com.clahbot.results

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext

/**
 * Hands the capture loop's episode flag to the overlay's thread.
 *
 * The results watcher polls and captures on `Dispatchers.Default`, while the
 * flag target writes overlay Views that may only be touched from the thread
 * that built the hierarchy. Routing the flag through the main dispatcher is
 * what stops a detected results screen from aborting capture with
 * `CalledFromWrongThreadException` and losing the record.
 */
class EpisodeEffectsBridge(
    private val mainDispatcher: CoroutineDispatcher,
    private val onChange: (Boolean) -> Unit
) {
    /** Delivers [active] on the main dispatcher, preserving call order. */
    suspend fun setActive(active: Boolean) {
        withContext(mainDispatcher) { onChange(active) }
    }
}
