package com.clahbot.recovery

import java.io.File
import java.nio.file.Files
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * The watchdog writes recovery counters from the accessibility service, which
 * runs independently of the dashboard. Construction must load the persisted file
 * so a write before any refresh() cannot clobber taught rules and stats, and an
 * unreadable/corrupt original must be preserved aside rather than left to be
 * overwritten by the next save.
 */
class RecoveryRepositoryTest {

    private lateinit var directory: File
    private lateinit var file: File
    private lateinit var screenDir: File

    @Before
    fun setUp() {
        directory = Files.createTempDirectory("clahbot-recovery").toFile()
        file = File(directory, "recovery.json")
        screenDir = File(directory, "unknown_screens")
    }

    @After
    fun tearDown() {
        directory.deleteRecursively()
    }

    private fun repository() = RecoveryRepository(file, screenDir)

    @Test
    fun `a write before any refresh preserves persisted stats and taught rules`() {
        file.writeText(
            """{"stats":{"recovered":5,"stopped":2,"unknown":1},"rules":[{"id":"taught1","name":"Taught","action":"TAP_CLOSE_X","matchHash":"abc"}],"unknown":[],"attempts":[]}"""
        )

        // Service-style: construct and write immediately, with no refresh() first.
        val repository = repository()
        repository.noteRecovered()

        val reloaded = repository()
        reloaded.refresh()
        assertEquals(6, reloaded.state.value.stats.recovered)
        assertEquals(2, reloaded.state.value.stats.stopped)
        assertEquals(1, reloaded.userRules().size)
        assertEquals("taught1", reloaded.userRules().single().id)
    }

    @Test
    fun `an unreadable file is preserved aside instead of left to be overwritten`() {
        assertTrue(file.mkdir())

        val repository = repository()
        assertEquals(0, repository.state.value.stats.recovered)
        assertFalse("the unreadable recovery file was left in place to be overwritten", file.exists())
        val quarantined = directory.listFiles()?.any { it.name.contains("corrupt") } ?: false
        assertTrue("the unreadable recovery file was not preserved", quarantined)
    }

    @Test
    fun `a corrupt file is preserved aside instead of left to be overwritten`() {
        file.writeText("{ this is not json")

        val repository = repository()
        assertEquals(0, repository.state.value.stats.recovered)
        val quarantined = directory.listFiles()?.any { it.name.contains("corrupt") } ?: false
        assertTrue("the corrupt recovery file was not preserved", quarantined)
    }
}