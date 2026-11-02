package com.clahbot.data

import com.clahbot.data.models.AttackRecord
import com.clahbot.data.models.GameSession
import com.clahbot.data.models.SessionAggregator
import com.clahbot.data.models.totalDarkElixir
import com.clahbot.data.models.totalElixir
import com.clahbot.data.models.totalGold
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.nio.file.Files

class SessionRepositoryTest {

    private lateinit var directory: File
    private lateinit var file: File
    private lateinit var repository: SessionRepository

    @Before
    fun setUp() {
        directory = Files.createTempDirectory("clahbot-sessions").toFile()
        file = File(directory, "sessions.json")
        repository = SessionRepository.forFile(file)
    }

    @After
    fun tearDown() {
        directory.deleteRecursively()
    }

    @Test
    fun ensureActiveCreatesExactlyOneSession() = runBlocking {
        val first = repository.ensureActive(1_000L)
        val second = repository.ensureActive(2_000L)
        assertEquals(first.id, second.id)
        assertEquals(1, repository.refresh().size)
        assertTrue(first.active)
    }

    @Test
    fun recoveryClosesAnUnfinishedSessionAtItsLastUpdate() = runBlocking {
        repository.ensureActive(1_000L)
        repository.addMacroToActive(7L, now = 2_000L)
        repository.touchActive(9_000L)
        val recovered = repository.recoverOpenSessions(20_000L)
        assertNotNull(recovered)
        assertEquals(GameSession.RECOVERED, recovered?.status)
        assertEquals(9_000L, recovered?.endedAt)
        assertEquals(20_000L, recovered?.lastUpdatedAt)

        val reloaded = SessionRepository.forFile(file).refresh().first()
        assertEquals(GameSession.RECOVERED, reloaded.status)
        assertEquals(9_000L, reloaded.endedAt)
    }

    @Test
    fun recoveryDropsAnEmptySession() = runBlocking {
        repository.ensureActive(1_000L)
        repository.touchActive(9_000L)
        assertNull(repository.recoverOpenSessions(20_000L))
        assertTrue(repository.refresh().isEmpty())
    }

    @Test
    fun totalsAddBonusAndExcludeIncompleteAttacks() = runBlocking {
        val session = repository.ensureActive(1_000L)
        val complete = record(
            id = 1,
            startedAt = 2_000L,
            gold = 100,
            elixir = 200,
            dark = 30,
            bonusGold = 10,
            bonusElixir = 20,
            bonusDark = 3,
            bonusState = AttackRecord.BONUS_CAPTURED
        )
        val noBonusPanel = record(
            id = 2,
            startedAt = 3_000L,
            gold = 50,
            elixir = 60,
            dark = 7,
            bonusState = AttackRecord.BONUS_NONE
        )
        val bonusUnread = record(
            id = 3,
            startedAt = 4_000L,
            gold = 999,
            elixir = 999,
            dark = 999,
            bonusState = AttackRecord.BONUS_NOT_CAPTURED
        )
        val noLoot = record(
            id = 4,
            startedAt = 5_000L,
            gold = null,
            elixir = null,
            dark = null,
            bonusState = AttackRecord.BONUS_NONE
        )
        val updated = repository.syncWithRecords(
            listOf(complete, noBonusPanel, bonusUnread, noLoot)
        )!!
        assertEquals(session.id, updated.id)
        assertEquals(4, updated.attackCount)
        assertEquals(2, updated.incompleteAttacks)
        assertEquals(150L, updated.gold)
        assertEquals(260L, updated.elixir)
        assertEquals(37L, updated.darkElixir)
        assertEquals(10L, updated.bonusGold)
        assertEquals(20L, updated.bonusElixir)
        assertEquals(3L, updated.bonusDarkElixir)
    }

    @Test
    fun recordTotalsIncludeBonusForDisplay() {
        val totals = SessionAggregator.totals(
            listOf(
                record(
                    id = 1,
                    startedAt = 1,
                    gold = 100,
                    elixir = 200,
                    dark = 30,
                    bonusGold = 5,
                    bonusElixir = 6,
                    bonusDark = 7,
                    bonusState = AttackRecord.BONUS_CAPTURED
                )
            )
        )
        assertEquals(105L, totals.totalGold)
        assertEquals(206L, totals.totalElixir)
        assertEquals(37L, totals.totalDarkElixir)
    }

    @Test
    fun hugeTotalsKeepEveryDigit() {
        val big = 999_999_999_999L
        val totals = SessionAggregator.totals(
            listOf(record(id = 1, startedAt = 1, gold = big, elixir = big, dark = big))
        )
        assertEquals(big, totals.totalGold)
        assertEquals(big, totals.totalElixir)
        assertEquals(big, totals.totalDarkElixir)
        assertEquals(
            "999,999,999,999",
            String.format(java.util.Locale.US, "%,d", totals.totalGold)
        )
    }

    @Test
    fun aRecordThatStartedBeforeTheSessionIsAttachedWhenExplicitlyIncluded() = runBlocking {
        repository.ensureActive(10_000L)
        val early = record(id = 7, startedAt = 1_000L, gold = 100, elixir = 200, dark = 30)
        val ignored = repository.syncWithRecords(listOf(early))!!
        assertEquals(0, ignored.attackCount)
        assertTrue(7L !in ignored.attackIds)

        val attached = repository.syncWithRecords(
            records = listOf(early),
            includeIds = setOf(7L)
        )!!
        assertEquals(1, attached.attackCount)
        assertTrue(7L in attached.attackIds)
        assertEquals(100L, attached.gold)
    }

    @Test
    fun editedRecordIsRecomputedInPlace() = runBlocking {
        repository.ensureActive(1_000L)
        repository.syncWithRecords(
            listOf(record(id = 1, startedAt = 2_000L, gold = 10, elixir = 20, dark = 1))
        )
        val updated = repository.syncWithRecords(
            listOf(record(id = 1, startedAt = 2_000L, gold = 99, elixir = 88, dark = 7))
        )!!
        assertEquals(1, updated.attackIds.size)
        assertEquals(99L, updated.gold)
        assertEquals(88L, updated.elixir)
        assertEquals(7L, updated.darkElixir)
    }

    @Test
    fun closeActiveRecordsTheEndAndKeepsTheSession() = runBlocking {
        val session = repository.ensureActive(1_000L)
        repository.syncWithRecords(
            listOf(record(id = 1, startedAt = 2_000L, gold = 10, elixir = 20, dark = 1))
        )
        val closed = repository.closeActive(now = 61_000L)
        assertEquals(GameSession.CLOSED, closed?.status)
        assertEquals(61_000L, closed?.endedAt)
        assertEquals(60_000L, closed?.durationMs)
        assertEquals(session.id, repository.refresh().first().id)
        assertNull(repository.active())
    }

    @Test
    fun closeActiveDropsAnEmptySessionWithoutPlayback() = runBlocking {
        repository.ensureActive(1_000L)
        repository.closeActive(now = 61_000L)
        assertTrue(repository.refresh().isEmpty())
        assertTrue(!file.exists() || !file.readText().contains("\"startedAt\""))
    }

    @Test
    fun closeActiveKeepsAPlaybackOnlySession() = runBlocking {
        repository.ensureActive(1_000L)
        repository.addMacroToActive(42L, now = 2_000L)
        repository.closeActive(now = 61_000L)
        assertEquals(1, repository.refresh().size)
        assertEquals(listOf(42L), repository.refresh().first().macroIds)
    }

    @Test
    fun oldRecordsAreNotAddedToANewSession() = runBlocking {
        val old = record(id = 1, startedAt = 500L, gold = 1, elixir = 1, dark = 1)
        repository.ensureActive(1_000L)
        val updated = repository.syncWithRecords(listOf(old))!!
        assertEquals(0, updated.attackCount)
        assertTrue(updated.attackIds.isEmpty())
    }

    @Test
    fun sessionsFileIsSeparateAndAtomic() = runBlocking {
        repository.ensureActive(1_000L)
        assertTrue(file.exists())
        assertTrue(!File(directory, "attacks.json").exists())
        val text = file.readText()
        assertTrue(text.contains("\"schemaVersion\":1"))
    }

    private fun record(
        id: Long,
        startedAt: Long,
        gold: Long?,
        elixir: Long?,
        dark: Long?,
        bonusGold: Long? = null,
        bonusElixir: Long? = null,
        bonusDark: Long? = null,
        bonusState: String? = AttackRecord.BONUS_NONE
    ) = AttackRecord(
        id = id,
        startedAt = startedAt,
        endedAt = startedAt + 30_000L,
        durationMs = 30_000L,
        confirmed = true,
        gold = gold,
        elixir = elixir,
        darkElixir = dark,
        bonusGold = bonusGold,
        bonusElixir = bonusElixir,
        bonusDarkElixir = bonusDark,
        bonusState = bonusState,
        complete = gold != null && elixir != null && dark != null
    )
}
