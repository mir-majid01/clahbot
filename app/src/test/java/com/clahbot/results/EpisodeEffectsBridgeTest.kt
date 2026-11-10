package com.clahbot.results

import java.util.concurrent.Executors
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

/**
 * The capture loop runs on Dispatchers.Default and the episode-active target
 * mutates an overlay View, so the hand-off must land on the main dispatcher.
 * A single-thread executor stands in for Main off-device: if the bridge ever
 * stops hopping, the target runs on the caller's worker thread and these fail.
 */
class EpisodeEffectsBridgeTest {

    @Test
    fun `the overlay target runs on the main dispatcher not the capture worker`() {
        val main = Executors.newSingleThreadExecutor { runnable ->
            Thread(runnable, "fake-main")
        }.asCoroutineDispatcher()
        try {
            var targetThread: String? = null
            val bridge = EpisodeEffectsBridge(mainDispatcher = main) {
                targetThread = Thread.currentThread().name
            }
            val worker = runBlocking(Dispatchers.Default) {
                val caller = Thread.currentThread().name
                bridge.setActive(true)
                caller
            }
            // kotlinx.coroutines appends " @coroutine#N" to the current thread name.
            assertEquals("the target must be touched on Main", "fake-main", baseName(targetThread))
            assertNotEquals(
                "the target ran on the capture worker",
                baseName(worker),
                baseName(targetThread)
            )
        } finally {
            main.close()
        }
    }

    @Test
    fun `both activation edges reach the target in order on main`() {
        val main = Executors.newSingleThreadExecutor { runnable ->
            Thread(runnable, "fake-main")
        }.asCoroutineDispatcher()
        try {
            val seen = mutableListOf<Boolean>()
            val threads = mutableSetOf<String>()
            val bridge = EpisodeEffectsBridge(mainDispatcher = main) { active ->
                seen += active
                threads += baseName(Thread.currentThread().name)!!
            }
            runBlocking(Dispatchers.Default) {
                bridge.setActive(true)
                bridge.setActive(false)
            }
            assertEquals(listOf(true, false), seen)
            assertEquals(setOf("fake-main"), threads)
        } finally {
            main.close()
        }
    }

    private fun baseName(thread: String?): String? = thread?.substringBefore(" @")
}