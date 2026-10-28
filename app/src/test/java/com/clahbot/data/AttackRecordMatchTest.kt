package com.clahbot.data

import com.clahbot.data.models.AttackRecord
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.nio.file.Files

class AttackRecordMatchTest {

    private lateinit var directory: File
    private lateinit var repository: AttackRepository

    @Before
    fun setUp() {
        directory = Files.createTempDirectory("autoclash-match").toFile()
        repository = AttackRepository(File(directory, "attacks.json"))
    }

    @After
    fun tearDown() {
        directory.deleteRecursively()
    }

    private fun record(
        id: Long,
        startedAt: Long = 100_000L,
        gold: Long? = 500,
        elixir: Long? = 600,
        dark: Long? = 7,
        hash: String? = "abc",
        outcome: String? = "victory"
    ) = AttackRecord(
        id = id,
        startedAt = startedAt,
        endedAt = startedAt + 1,
        durationMs = 1,
        gold = gold,
        elixir = elixir,
        darkElixir = dark,
        screenHash = hash,
        outcome = outcome,
        confirmed = true
    )

    @Test
    fun theSameScreenHashWithinTheWindowIsAMatch() = runBlocking {
        repository.add(record(1))
        assertTrue(repository.hasRecentMatch("abc", null, null, null, "victory", since = 50_000L))
    }

    @Test
    fun theSameScreenHashOutsideTheWindowIsNotAMatch() = runBlocking {
        repository.add(record(1))
        assertFalse(repository.hasRecentMatch("abc", null, null, null, "victory", since = 200_000L))
    }

    @Test
    fun theSameLootTripleWithinTheWindowIsAMatchWhenThereIsNoHash() = runBlocking {
        repository.add(record(1, hash = null))
        assertTrue(repository.hasRecentMatch(null, 500, 600, 7, "victory", since = 50_000L))
    }

    @Test
    fun aDifferentOutcomeOrValueIsNotAMatch() = runBlocking {
        repository.add(record(1, hash = null))
        assertFalse(repository.hasRecentMatch(null, 501, 600, 7, "victory", since = 50_000L))
        assertFalse(repository.hasRecentMatch(null, 500, 600, 7, "defeat", since = 50_000L))
    }

    @Test
    fun noValuesAndNoHashIsNeverAMatch() = runBlocking {
        repository.add(record(1, gold = null, elixir = null, dark = null, hash = null))
        assertFalse(repository.hasRecentMatch(null, null, null, null, "victory", since = 50_000L))
    }
}
