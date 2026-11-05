package com.clahbot.results

import com.clahbot.data.AttackRepository
import com.clahbot.data.models.AttackRecord
import com.clahbot.ocr.OcrBox
import com.clahbot.ocr.ResultsOutcome
import com.clahbot.ocr.ResultsRead
import java.io.File
import java.nio.file.Files
import java.util.Locale
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Ten result screens pushed through the same pieces the capture loop uses:
 * EpisodePolicy for stability, ResultsToAttackRecord for the row, and
 * AttackRepository for the file. Frame timestamps are real wall-clock values so
 * the duplicate window behaves exactly as it does on the phone. These are
 * simulated frames, not device captures.
 */
class LootCycleSimulationTest {

    private lateinit var directory: File
    private lateinit var repository: AttackRepository

    @Before
    fun setUp() {
        directory = Files.createTempDirectory("clahbot-loot").toFile()
        repository = AttackRepository(File(directory, "attacks.json"))
        runBlocking { repository.refresh() }
    }

    @After
    fun tearDown() {
        directory.deleteRecursively()
    }

    /** One cycle plus what the pipeline actually did with it. */
    private class Cycle(
        val index: Int,
        val session: CaptureSession,
        val hash: String,
        val gold: Long?,
        val elixir: Long?,
        val dark: Long?,
        val bonusGold: Long?
    ) {
        var detectedMs: Long = -1L
        var persistMs: Long = -1L
        var earlySaved = false
        var duplicate = false
        var record: AttackRecord? = null

        fun withHash(value: String) = Cycle(
            index = index,
            session = session,
            hash = value,
            gold = gold,
            elixir = elixir,
            dark = dark,
            bonusGold = bonusGold
        )
    }

    private fun read(
        gold: Long?,
        elixir: Long?,
        dark: Long? = null,
        bonus: Long? = null,
        outcome: ResultsOutcome = ResultsOutcome.VICTORY
    ) = ResultsRead(
        outcome = outcome,
        stars = null,
        gold = gold,
        elixir = elixir,
        darkElixir = dark,
        bonusGold = bonus,
        bonusElixir = bonus,
        bonusDarkElixir = bonus,
        balanceGold = null,
        balanceElixir = null,
        balanceDarkElixir = null,
        confidence = 1.0,
        needsReview = false,
        issues = emptyList(),
        bonusPanel = bonus != null
    ).let {
        // Plausible proportions: the dark row is always the small one.
        it.copy(bonusDarkElixir = bonus?.let { value -> value / 100L })
    }

    private fun boxes() = listOf(
        OcrBox(100, 200, 300, 240),
        OcrBox(100, 250, 300, 290),
        OcrBox(100, 300, 300, 340)
    )

    /**
     * A realistic screen: one poll sees nothing, an optional wobbly read, the
     * loot settling, then the bonus panel stopping its animation.
     */
    private fun frames(
        base: Long,
        gold: Long?,
        elixir: Long?,
        dark: Long?,
        bonus: Long?,
        wobble: Boolean,
        lateBonus: Boolean
    ): List<EpisodeFrame> {
        val frames = mutableListOf<EpisodeFrame>()
        var at = base
        fun add(read: ResultsRead?, panel: Boolean) {
            frames.add(
                EpisodeFrame(
                    atMs = at,
                    resultsScreen = read != null,
                    read = read,
                    bonusRowBoxes = if (panel) boxes() else emptyList(),
                    bonusRowCount = if (panel) 3 else 0
                )
            )
            at += 250L
        }
        add(null, false)
        if (wobble) add(read(gold, elixir?.let { it + 1_000L }, dark), false)
        repeat(3) { add(read(gold, elixir, dark), false) }
        if (bonus != null) {
            repeat(if (lateBonus) 2 else 3) { add(read(gold, elixir, dark, bonus), true) }
            repeat(3) { add(read(gold, elixir, dark, bonus), true) }
        } else {
            repeat(20) { add(read(gold, elixir, dark), false) }
        }
        return frames
    }

    private fun buildCycle(
        index: Int,
        base: Long,
        gold: Long?,
        elixir: Long?,
        dark: Long? = null,
        bonus: Long? = null,
        hash: String = "hash-$index"
    ) = Cycle(
        index = index,
        session = CaptureSession(
            id = base,
            startedAt = base,
            macroId = 11L,
            macroName = "TH farm",
            speed = 1f,
            repeat = "loop"
        ),
        hash = hash,
        gold = gold,
        elixir = elixir,
        dark = dark,
        bonusGold = bonus
    )

    private fun run(
        index: Int,
        base: Long,
        gold: Long?,
        elixir: Long?,
        dark: Long? = null,
        bonus: Long? = null,
        wobble: Boolean = index % 2 == 0,
        lateBonus: Boolean = index % 3 == 0,
        hash: String = "hash-$index"
    ): Cycle = runCycle(
        buildCycle(index, base, gold, elixir, dark, bonus, hash),
        frames(base, gold, elixir, dark, bonus, wobble, lateBonus)
    )

    /**
     * Mirrors the capture loop: write the record on the stability edge, keep
     * reading the bonus in the background, then update the same row.
     */
    private fun runCycle(cycle: Cycle, frames: List<EpisodeFrame>): Cycle = runBlocking {
        val policy = EpisodePolicy(startMs = frames.first().atMs)
        val firstSighting = frames.first { it.resultsScreen }.atMs
        var earlySaved = false
        var closed: EpisodeResult? = null
        var settledAt = 0L
        for (frame in frames) {
            val decision = policy.onFrame(frame)
            if (decision is EpisodeDecision.Finish) {
                closed = decision.result
                break
            }
            if (!policy.stableJustArrived()) continue
            val episode = policy.finishNow(frame.atMs)
            if (episode.gold == null && episode.elixir == null) continue
            val duplicate = repository.hasRecentMatch(
                screenHash = cycle.hash,
                gold = episode.gold,
                elixir = episode.elixir,
                darkElixir = episode.darkElixir,
                outcome = episode.outcome?.name?.lowercase(Locale.US),
                since = System.currentTimeMillis() - ResultsCaptureCoordinator.DUPLICATE_WINDOW_MS
            )
            if (duplicate) {
                cycle.duplicate = true
                return@runBlocking cycle
            }
            val started = System.nanoTime()
            cycle.record = repository.add(
                ResultsToAttackRecord.fromEpisode(
                    session = cycle.session,
                    episode = episode,
                    playbackEndedAt = frame.atMs,
                    interrupted = false,
                    screenshotPath = null,
                    sidecarPath = null,
                    screenHash = cycle.hash
                )
            )
            cycle.persistMs = (System.nanoTime() - started) / 1_000_000L
            cycle.detectedMs = frame.atMs - firstSighting
            cycle.earlySaved = true
            earlySaved = true
            settledAt = frame.atMs
        }
        val episode = closed ?: policy.finishNow(
            settledAt.takeIf { it > 0L } ?: frames.last().atMs
        )
        val record = ResultsToAttackRecord.fromEpisode(
            session = cycle.session,
            episode = episode,
            playbackEndedAt = frames.last().atMs,
            interrupted = false,
            screenshotPath = null,
            sidecarPath = null,
            screenHash = cycle.hash
        )
        cycle.record = if (earlySaved) {
            repository.update(record) ?: error("the early record disappeared")
        } else {
            repository.add(record)
        }
        requireNotNull(cycle.record)
        cycle
    }

    private fun tenCycles(): List<Cycle> {
        val now = System.currentTimeMillis()
        return (0 until 10).map { index ->
            run(
                index = index,
                base = now + index * 100_000L,
                gold = 500_000L + index * 10_000L,
                elixir = 600_000L + index * 10_000L,
                dark = if (index % 3 == 0) 5_000L + index else null,
                bonus = if (index % 2 == 0) 370_000L + index else null
            )
        }
    }

    @Test
    fun `ten result cycles each persist once right after the loot settles`() {
        val cycles = tenCycles()

        assertEquals(10, cycles.count { it.earlySaved })
        assertEquals(0, cycles.count { it.duplicate })
        assertEquals(10, runBlocking { repository.records.value }.size)
        val worstDetection = cycles.maxOf { it.detectedMs }
        val worstWrite = cycles.maxOf { it.persistMs }
        assertTrue(
            "worst detection=${worstDetection}ms worst write=${worstWrite}ms",
            worstDetection <= 500L
        )
        assertTrue("worst write=${worstWrite}ms", worstWrite < 2_000L)
        cycles.forEach { cycle ->
            val record = requireNotNull(cycle.record) { "cycle ${cycle.index} lost its record" }
            assertEquals(cycle.gold, record.gold)
            assertEquals(cycle.elixir, record.elixir)
            assertEquals(AttackRecord.LOOT_OCR, record.lootSource)
            assertTrue(record.complete)
            assertFalse(record.needsReviewDefinition)
            assertEquals(cycle.session.id, record.id)
        }
    }

    @Test
    fun `the dashboard source already holds the row when the write returns`() {
        val now = System.currentTimeMillis()
        var lastSeenCount = 0
        (0 until 10).forEach { index ->
            val base = now + index * 100_000L
            val cycle = run(
                index = index,
                base = base,
                gold = 700_000L + index * 1_000L,
                elixir = 800_000L + index * 1_000L
            )
            lastSeenCount++
            val inFlow = repository.records.value.count { it.id == cycle.session.id }
            assertEquals(
                "cycle ${index} refresh=${inFlow}",
                1,
                inFlow
            )
            assertEquals(lastSeenCount, repository.records.value.size)
        }
    }

    @Test
    fun `the bonus that lands later updates the same row instead of adding one`() {
        val base = System.currentTimeMillis()
        val cycle = run(0, base, 1_000_000L, 1_100_000L, dark = 6_000L, bonus = 370_000L)

        val record = requireNotNull(cycle.record)
        assertEquals(1, runBlocking { repository.records.value }.size)
        assertEquals(370_000L, record.bonusGold)
        assertEquals(AttackRecord.BONUS_CAPTURED, record.bonusState)
        assertFalse(record.needsReviewDefinition)
    }

    @Test
    fun `a missing dark row is stored as a gap and still counts`() {
        val base = System.currentTimeMillis()
        val cycle = run(0, base, 1_250_000L, 1_300_000L, dark = null, bonus = 370_000L)

        val record = requireNotNull(cycle.record)
        assertEquals(null, record.darkElixir)
        assertTrue(record.complete)
        assertFalse(record.needsReviewDefinition)
        assertTrue(record.note.orEmpty().contains("Dark elixir unreadable"))
        assertTrue(record.note.orEmpty().contains("gold and elixir still count"))
    }

    @Test
    fun `a cycle with no readable loot is recorded as an explicit miss`() {
        val base = System.currentTimeMillis()
        val cycle = run(1, base, gold = null, elixir = null)

        assertFalse(cycle.earlySaved)
        val record = requireNotNull(cycle.record)
        assertFalse(record.complete)
        assertTrue(record.needsReviewDefinition)
        assertEquals(AttackRecord.LOOT_NONE, record.lootSource)
        assertTrue(record.reviewReason.orEmpty().contains("Loot unreadable"))
    }

    @Test
    fun `the same screen seen twice becomes one record`() {
        val base = System.currentTimeMillis()
        val first = run(2, base, 900_000L, 950_000L, dark = 4_000L)
        assertNotNull(first.record)

        val replay = buildCycle(3, base + 1_000L, 900_000L, 950_000L, 4_000L)
            .withHash(first.hash)
        val second = runCycle(
            replay,
            frames(base + 1_000L, 900_000L, 950_000L, 4_000L, null, wobble = false, lateBonus = false)
        )

        assertTrue("a repeated screen must be recognised as a duplicate", second.duplicate)
        assertEquals(1, runBlocking { repository.records.value }.size)
    }

    @Test
    fun `an older screen with near-identical loot is not swallowed as a duplicate`() {
        val now = System.currentTimeMillis()
        run(4, now - 90_000L, 900_000L, 950_000L, dark = 4_000L, hash = "hash-old")

        val next = run(5, now, 900_001L, 950_000L, dark = 4_000L, hash = "hash-new")

        assertFalse(next.duplicate)
        assertNotNull(next.record)
        assertEquals(2, runBlocking { repository.records.value }.size)
    }

    @Test
    fun `the totals the dashboard reads survive a reopen`() {
        val cycles = tenCycles()
        val expectedGold = cycles.sumOf { it.gold ?: 0L }
        val expectedElixir = cycles.sumOf { it.elixir ?: 0L }

        val reopened = AttackRepository(File(directory, "attacks.json"))
        val records = runBlocking { reopened.refresh() }
        assertEquals(10, records.size)
        assertEquals(expectedGold, records.sumOf { it.gold ?: 0L })
        assertEquals(expectedElixir, records.sumOf { it.elixir ?: 0L })
        assertEquals(10, records.distinctBy { it.id }.size)
    }
}
