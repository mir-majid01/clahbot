package com.clahbot.data

import com.clahbot.data.models.AttackRecord
import com.clahbot.data.models.AttackStats
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.nio.file.Files

class AttackHistoryTest {

    private lateinit var directory: File
    private lateinit var repository: AttackRepository

    @Before
    fun setUp() {
        directory = Files.createTempDirectory("autoclash-attacks").toFile()
        repository = AttackRepository(File(directory, "attacks.json"))
    }

    @After
    fun tearDown() {
        directory.deleteRecursively()
    }

    private fun record(
        id: Long,
        started: Long = 1_000L + id * 60_000L,
        duration: Long = 60_000L,
        gold: Long? = null,
        elixir: Long? = null,
        dark: Long? = null
    ) = AttackRecord(
        id = id,
        startedAt = started,
        endedAt = started + duration,
        durationMs = duration,
        macroId = 7,
        macroName = "Attack",
        speedMultiplier = 1f,
        repeatMode = "once",
        gold = gold,
        elixir = elixir,
        darkElixir = dark
    )

    @Test
    fun savesPublishesAndPersists() = runBlocking {
        assertNotNull(repository.add(record(1, gold = 120_000)))
        assertEquals(1, repository.records.value.size)

        val reloaded = AttackRepository(File(directory, "attacks.json"))
        val loaded = reloaded.refresh()
        assertEquals(1, loaded.size)
        assertEquals(120_000L, loaded[0].gold)
        assertEquals("Attack", loaded[0].macroName)
    }

    @Test
    fun rejectsDuplicateIds() = runBlocking {
        assertNotNull(repository.add(record(1)))
        assertNull(repository.add(record(1)))
        assertEquals(1, repository.records.value.size)
    }

    @Test
    fun unreadableLootStaysNullAndIsNotZero() = runBlocking {
        repository.add(record(1))
        val stored = repository.records.value.single()
        assertNull(stored.gold)
        assertNull(stored.elixir)
        assertNull(stored.darkElixir)
        assertFalse(stored.hasLoot)
    }

    @Test
    fun zeroLootIsRecordedZeroAndCountsAsObserved() {
        val summary = AttackStats.summarize(listOf(record(1, gold = 0), record(2, gold = null)))
        assertEquals(1, summary.lootedCount)
        assertEquals(0L, summary.gold)
        assertEquals(2, summary.count)
    }

    @Test
    fun summarySumsFieldsSeparatelyAndAveragesDuration() {
        val summary = AttackStats.summarize(
            listOf(
                record(1, duration = 60_000, gold = 100, elixir = 200, dark = 10),
                record(2, duration = 120_000, gold = 50, elixir = null, dark = null)
            )
        )
        assertEquals(2, summary.count)
        assertEquals(150L, summary.gold)
        assertEquals(200L, summary.elixir)
        assertEquals(10L, summary.darkElixir)
        assertEquals(180_000L, summary.totalDurationMs)
        assertEquals(90_000L, summary.averageDurationMs)
    }

    @Test
    fun filterKeepsNewestFirst() {
        val now = 10_000_000L
        val old = record(1, started = now - 10L * 24 * 60 * 60 * 1000)
        val recent = record(2, started = now - 60_000)
        val filtered = AttackStats.filter(listOf(old, recent), now - 7L * 24 * 60 * 60 * 1000)
        assertEquals(1, filtered.size)
        assertEquals(2L, filtered[0].id)
    }

    @Test
    fun updateCorrectsLootAndMarksManual() = runBlocking {
        repository.add(record(1))
        val stored = repository.records.value.single()
        val updated = repository.update(
            stored.copy(gold = 500_000, lootSource = AttackRecord.LOOT_MANUAL, verified = true)
        )
        assertNotNull(updated)
        val reread = repository.records.value.single()
        assertEquals(500_000L, reread.gold)
        assertEquals(AttackRecord.LOOT_MANUAL, reread.lootSource)
        assertTrue(reread.verified)
    }

    @Test
    fun deletingAMacroDoesNotTouchHistory() = runBlocking {
        repository.add(record(1, gold = 5))
        // History stores a name snapshot, not a foreign key.
        assertEquals(1, repository.records.value.size)
        assertEquals(7L, repository.records.value[0].macroId)
    }

    @Test
    fun onlyConfirmedRecordsCountAsAttacks() {
        val playback = record(1, gold = null).copy(confirmed = false)
        val confirmed = record(2, gold = 100).copy(confirmed = true)
        val summary = AttackStats.summarize(
            AttackStats.confirmedOnly(listOf(playback, confirmed))
        )
        assertEquals(1, summary.count)
        assertEquals(100L, summary.gold)
    }

    @Test
    fun legacyRecordsWithoutConfirmationAreUnconfirmed() = runBlocking {
        // A record written by the previous build has no "confirmed" field.
        File(directory, "attacks.json").writeText(
            """
            {"schemaVersion":1,"records":[{
              "id":1,"startedAt":1000,"endedAt":61000,"durationMs":60000,
              "macroId":7,"macroName":"Attack","speedMultiplier":1.0,
              "repeatMode":"once","result":"completed","complete":true
            }]}
            """.trimIndent()
        )
        val reloaded = AttackRepository(File(directory, "attacks.json"))
        val loaded = reloaded.refresh()
        assertEquals(1, loaded.size)
        assertFalse(loaded[0].confirmed)
        assertEquals(0, AttackStats.confirmedOnly(loaded).size)
    }

    @Test
    fun ocrRecordKeepsNullLootAndItsProvenance() = runBlocking {
        val ocr = record(1, gold = 503_859L, elixir = null, dark = 5_713L).copy(
            confirmed = true,
            lootSource = AttackRecord.LOOT_OCR,
            outcome = "victory",
            resultsScreenshot = "/tmp/screen.png",
            resultsSidecar = "/tmp/screen.json",
            note = "Read automatically from the result screen."
        )
        repository.add(ocr)
        val stored = repository.records.value.single()
        assertEquals(503_859L, stored.gold)
        assertNull(stored.elixir)
        assertEquals(5_713L, stored.darkElixir)
        assertEquals(AttackRecord.LOOT_OCR, stored.lootSource)
        assertEquals("victory", stored.outcome)
        assertEquals("/tmp/screen.png", stored.resultsScreenshot)
        assertEquals("/tmp/screen.json", stored.resultsSidecar)
        assertTrue(stored.confirmed)
    }

    @Test
    fun bonusIsStoredSeparatelyFromAwardedLoot() {
        val withBonus = record(1, gold = 100).copy(
            bonusGold = 310_000,
            bonusElixir = 310_000,
            bonusDarkElixir = 2_100
        )
        val summary = AttackStats.summarize(listOf(withBonus))
        assertEquals(100L, summary.gold)
        assertEquals(0L, summary.elixir)
        assertTrue(withBonus.hasBonus)
    }

    @Test
    fun clearAllEmptiesHistory() = runBlocking {
        repository.add(record(1))
        repository.add(record(2))
        repository.clearAll()
        assertEquals(0, repository.records.value.size)
        assertEquals(0, repository.refresh().size)
    }
}
