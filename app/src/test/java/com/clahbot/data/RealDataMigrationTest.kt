package com.clahbot.data

import com.clahbot.data.models.GameSession
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files
import java.security.MessageDigest

/**
 * One-off checks on copies of the user's live files. Set
 * CLAHS_TEST_MACROS / CLAHS_TEST_ATTACKS to run them locally; CI skips them
 * instead of committing personal data.
 */
class RealDataMigrationTest {

    @Test
    fun realMacrosLoadWithoutLosingGestures() = runBlocking {
        val source = System.getenv("CLAHS_TEST_MACROS")?.let(::File)
        assumeTrue(source != null && source.isFile)
        val directory = Files.createTempDirectory("clahbot-real-macros").toFile()
        try {
            val copy = File(directory, "macros.json")
            source!!.copyTo(copy)
            val loaded = MacroRepository(copy).load()
            assertTrue(loaded.isNotEmpty())
            assertTrue(loaded.all { it.gestures.isNotEmpty() })
            assertEquals("Macro Oct 28 00:04", loaded.first { it.name.startsWith("Macro") }.name)
        } finally {
            directory.deleteRecursively()
        }
    }

    @Test
    fun sessionsNeverRewriteTheAttacksFile() = runBlocking {
        val source = System.getenv("CLAHS_TEST_ATTACKS")?.let(::File)
        assumeTrue(source != null && source.isFile)
        val directory = Files.createTempDirectory("clahbot-real-attacks").toFile()
        try {
            val attacksCopy = File(directory, "attacks.json")
            source!!.copyTo(attacksCopy)
            val before = sha256(attacksCopy)
            val sessions = SessionRepository.forFile(File(directory, "sessions.json"))
            sessions.ensureActive(1_000L)
            sessions.syncWithRecords(emptyList())
            sessions.closeActive(now = 2_000L)
            assertEquals(before, sha256(attacksCopy))
            assertEquals(GameSession.CLOSED, sessions.refresh().first().status)
        } finally {
            directory.deleteRecursively()
        }
    }

    private fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        return digest.digest(file.readBytes()).joinToString("") { "%02x".format(it) }
    }
}
