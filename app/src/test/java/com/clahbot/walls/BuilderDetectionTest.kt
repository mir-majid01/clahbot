package com.clahbot.walls

import com.clahbot.ocr.BuilderFaceKind
import com.clahbot.ocr.OcrBox
import com.clahbot.ocr.OcrLine
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Builder availability is read ONLY from the left-most fraction slot of the
 * bottom builder strip, and the slot's face decides whether that fraction
 * counts regular builders or the Goblin Builder offer.
 *
 * These tests stub the face sampler: they prove the arithmetic, the fail-closed
 * branches and the slot selection. They do not prove the bitmap sampler's
 * colour thresholds; that path is only verifiable on the device.
 */
class BuilderDetectionTest {

    private fun line(text: String) = OcrLine(text = text, box = null, elements = emptyList())

    private fun boxedLine(text: String, left: Int, top: Int, right: Int, bottom: Int) =
        OcrLine(text, OcrBox(left, top, right, bottom), emptyList())

    /** A normal human builder face above the badge. */
    private val regularFace: (OcrBox) -> BuilderFaceKind? = { BuilderFaceKind.REGULAR }

    /** The green Goblin Builder face above the badge. */
    private val goblinFace: (OcrBox) -> BuilderFaceKind? = { BuilderFaceKind.GOBLIN }

    // ---- signals that are not a builder count ------------------------------

    @Test
    fun noBuilderEvidenceIsUncertainAndNeverAvailable() {
        val reading = BuilderDetection.detect(listOf(line("Home village overview")))

        assertEquals(BuilderDetection.Status.UNCERTAIN, reading.status)
        assertEquals(0f, reading.confidence)
        assertFalse(reading.isAvailable)
    }

    @Test
    fun goblinBuilderOfferIsNotAnIndependentRegularBuilder() {
        val reading = BuilderDetection.detect(listOf(line("Goblin Builder available")))

        assertEquals(BuilderDetection.Status.NONE_AVAILABLE, reading.status)
        assertTrue(reading.goblinBuilderOffer)
        assertNull(reading.freeBuilders)
        assertFalse(reading.isAvailable)
    }

    @Test
    fun builderApprenticeIsNotAnIndependentRegularBuilder() {
        val reading = BuilderDetection.detect(listOf(line("Builder Apprentice assisting")))

        assertEquals(BuilderDetection.Status.UNCERTAIN, reading.status)
        assertTrue(reading.builderApprenticeHelper)
        assertFalse(reading.isAvailable)
    }

    @Test
    fun bobDetectionIsUncertainForTheRegularBuilderPath() {
        val reading = BuilderDetection.detect(listOf(line("BOB builder activity")))

        assertEquals(BuilderDetection.Status.UNCERTAIN, reading.status)
        assertTrue(reading.bOBDetected)
        assertFalse(reading.isAvailable)
    }

    @Test
    fun `a number next to the word builder is not a builder count`() {
        // The removed text fallback read the Builder's Hut level as a free
        // builder. Without a free/total fraction the answer is unknown.
        val reading = BuilderDetection.detect(listOf(line("Builder 3")))

        assertEquals(BuilderDetection.Status.UNCERTAIN, reading.status)
        assertNull(reading.freeBuilders)
        assertFalse(reading.isAvailable)
    }

    @Test
    fun `a builders hut list row is not availability evidence`() {
        val reading = BuilderDetection.detect(
            listOf(line("5"), line("Builder's Hut"))
        )

        assertEquals(BuilderDetection.Status.UNCERTAIN, reading.status)
        assertNull(reading.freeBuilders)
        assertFalse(reading.isAvailable)
    }

    // ---- regular fraction, normal face -------------------------------------

    @Test
    fun `bottom right free over total is the regular builder count`() {
        val reading = BuilderDetection.detect(
            listOf(boxedLine("1/5", 1900, 1000, 1960, 1040)),
            2000,
            1080,
            regularFace
        )

        assertEquals(BuilderDetection.Status.AVAILABLE, reading.status)
        assertEquals(1, reading.freeBuilders)
        assertEquals(5, reading.totalBuilders)
        assertEquals(4, reading.busyBuilders)
        assertEquals(0.90f, reading.confidence, 0.0001f)
        assertEquals(BuilderFaceKind.REGULAR, reading.face)
        assertTrue(reading.isAvailable)
    }

    @Test
    fun `zero free is unavailable not an upgrade authorization`() {
        val reading = BuilderDetection.detect(
            listOf(boxedLine("0/5", 1900, 1000, 1960, 1040)),
            2000,
            1080,
            regularFace
        )

        assertEquals(BuilderDetection.Status.NONE_AVAILABLE, reading.status)
        assertEquals(0, reading.freeBuilders)
        assertEquals(5, reading.totalBuilders)
        assertEquals(5, reading.busyBuilders)
        assertFalse(reading.isAvailable)
    }

    @Test
    fun `a fraction outside the corner never reaches the authorization threshold`() {
        val reading = BuilderDetection.detect(
            listOf(boxedLine("1/5", 10, 10, 70, 50)),
            2000,
            1080,
            regularFace
        )

        assertEquals(BuilderDetection.Status.AVAILABLE, reading.status)
        assertEquals(1, reading.freeBuilders)
        assertEquals(0.65f, reading.confidence, 0.0001f)
        assertFalse(reading.isAvailable)
    }

    @Test
    fun `a goblin offer does not hide the regular builder fraction`() {
        val reading = BuilderDetection.detect(
            listOf(
                line("Goblin Builder offer"),
                boxedLine("2/5", 1900, 1000, 1960, 1040)
            ),
            2000,
            1080,
            regularFace
        )

        assertEquals(BuilderDetection.Status.AVAILABLE, reading.status)
        assertEquals(2, reading.freeBuilders)
        assertEquals(5, reading.totalBuilders)
        assertTrue(reading.goblinBuilderOffer)
        assertTrue(reading.goblinOfferKnown)
        assertTrue(reading.isAvailable)
    }

    @Test
    fun `the apprentice is a flag and the regular fraction still wins`() {
        val reading = BuilderDetection.detect(
            listOf(
                line("Builder Apprentice assisting"),
                boxedLine("1/5", 1900, 1000, 1960, 1040)
            ),
            2000,
            1080,
            regularFace
        )

        assertEquals(BuilderDetection.Status.AVAILABLE, reading.status)
        assertEquals(1, reading.freeBuilders)
        assertTrue(reading.builderApprenticeHelper)
        assertTrue(reading.isAvailable)
    }

    @Test
    fun `a fraction with an impossible total is ignored`() {
        val reading = BuilderDetection.detect(
            listOf(boxedLine("1/99", 1900, 1000, 1960, 1040)),
            2000,
            1080,
            regularFace
        )

        assertEquals(BuilderDetection.Status.UNCERTAIN, reading.status)
        assertNull(reading.freeBuilders)
        assertFalse(reading.isAvailable)
    }

    @Test
    fun `more free than total is a bad read and stays uncertain`() {
        val reading = BuilderDetection.detect(
            listOf(boxedLine("6/4", 1900, 1000, 1960, 1040)),
            2000,
            1080,
            regularFace
        )

        assertEquals(BuilderDetection.Status.UNCERTAIN, reading.status)
        assertNull(reading.freeBuilders)
        assertFalse(reading.isAvailable)
    }

    // ---- goblin face -------------------------------------------------------

    @Test
    fun `a goblin slot has zero free regular builders and one fewer total`() {
        val reading = BuilderDetection.detect(
            listOf(boxedLine("1/7", 1900, 1000, 1960, 1040)),
            2000,
            1080,
            goblinFace
        )

        assertEquals(BuilderDetection.Status.NONE_AVAILABLE, reading.status)
        assertEquals(0, reading.freeBuilders)
        assertEquals(6, reading.totalBuilders)
        assertEquals(6, reading.busyBuilders)
        assertTrue(reading.goblinBuilderOffer)
        assertTrue(reading.goblinOfferKnown)
        assertEquals(BuilderFaceKind.GOBLIN, reading.face)
        assertFalse(reading.isAvailable)
    }

    @Test
    fun `a goblin slot with an impossible regular total stays uncertain`() {
        // The laboratory badge also shows a face-like icon; `1/2` under a
        // goblin face would leave one regular builder, which the game never
        // has, so this must not become a builder count.
        val reading = BuilderDetection.detect(
            listOf(boxedLine("1/2", 1900, 1000, 1960, 1040)),
            2000,
            1080,
            goblinFace
        )

        assertEquals(BuilderDetection.Status.UNCERTAIN, reading.status)
        assertNull(reading.freeBuilders)
        assertNull(reading.totalBuilders)
        assertTrue(reading.goblinBuilderOffer)
        assertEquals(BuilderFaceKind.GOBLIN, reading.face)
        assertFalse(reading.isAvailable)
    }

    // ---- unclassifiable face ----------------------------------------------

    @Test
    fun `an unreadable face never becomes a regular builder count`() {
        val reading = BuilderDetection.detect(
            listOf(boxedLine("1/6", 1900, 1000, 1960, 1040)),
            2000,
            1080,
            { null }
        )

        assertEquals(BuilderDetection.Status.UNCERTAIN, reading.status)
        assertNull(reading.freeBuilders)
        assertNull(reading.face)
        assertFalse(reading.isAvailable)
    }

    @Test
    fun `no face sampler is treated as an unreadable face`() {
        val reading = BuilderDetection.detect(
            listOf(boxedLine("1/6", 1900, 1000, 1960, 1040)),
            2000,
            1080
        )

        assertEquals(BuilderDetection.Status.UNCERTAIN, reading.status)
        assertNull(reading.freeBuilders)
    }

    // ---- left-most slot only ----------------------------------------------

    @Test
    fun `the left-most fraction of the bottom-right strip is the builder count`() {
        val reading = BuilderDetection.detect(
            listOf(
                boxedLine("1/6", 1100, 900, 1180, 940),   // builders, left-most
                boxedLine("1/2", 1400, 900, 1480, 940),   // lab
                boxedLine("0/1", 1700, 900, 1780, 940)    // other building
            ),
            2000,
            1080,
            regularFace
        )

        assertEquals(BuilderDetection.Status.AVAILABLE, reading.status)
        assertEquals(1, reading.freeBuilders)
        assertEquals(6, reading.totalBuilders)
        assertEquals(0.90f, reading.confidence, 0.0001f)
    }

    @Test
    fun `several fractions on one ocr line still pick the left-most`() {
        val reading = BuilderDetection.detect(
            listOf(boxedLine("1/6 1/2 0/1", 1100, 900, 1780, 940)),
            2000,
            1080,
            regularFace
        )

        assertEquals(BuilderDetection.Status.AVAILABLE, reading.status)
        assertEquals(1, reading.freeBuilders)
        assertEquals(6, reading.totalBuilders)
    }

    @Test
    fun `a zero free builder badge left of the neighbours is still the one used`() {
        val reading = BuilderDetection.detect(
            listOf(
                boxedLine("0/6", 1100, 900, 1180, 940),   // builders, all busy
                boxedLine("1/2", 1400, 900, 1480, 940),   // lab would lie about free
                boxedLine("1/1", 1700, 900, 1780, 940)    // other building
            ),
            2000,
            1080,
            regularFace
        )

        assertEquals(BuilderDetection.Status.NONE_AVAILABLE, reading.status)
        assertEquals(0, reading.freeBuilders)
        assertEquals(6, reading.totalBuilders)
        assertEquals(6, reading.busyBuilders)
    }

    @Test
    fun `only the left-most slot's face is consulted`() {
        // The right slot has a normal face and 5 free builders; the left slot
        // is the goblin. Reading the right slot would invent five builders.
        val reading = BuilderDetection.detect(
            listOf(
                boxedLine("1/7", 1100, 900, 1180, 940),
                boxedLine("5/6", 1400, 900, 1480, 940)
            ),
            2000,
            1080,
            { box -> if (box.centerX < 1300) BuilderFaceKind.GOBLIN else BuilderFaceKind.REGULAR }
        )

        assertEquals(0, reading.freeBuilders)
        assertEquals(6, reading.totalBuilders)
        assertEquals(BuilderFaceKind.GOBLIN, reading.face)
    }

    // ---- totals are derived, never hardcoded ------------------------------

    @Test
    fun `five six and seven slots all keep free plus busy equal to total`() {
        data class Case(val badge: String, val free: Int, val total: Int)

        val cases = listOf(
            Case("2/5", 2, 5),
            Case("3/6", 3, 6),
            Case("4/7", 4, 7)
        )
        for (case in cases) {
            val reading = BuilderDetection.detect(
                listOf(boxedLine(case.badge, 1900, 1000, 1960, 1040)),
                2000,
                1080,
                regularFace
            )
            assertEquals("free for ${case.badge}", case.free, reading.freeBuilders)
            assertEquals("total for ${case.badge}", case.total, reading.totalBuilders)
            assertEquals(
                "free+busy for ${case.badge}",
                case.total,
                (reading.freeBuilders ?: -1) + (reading.busyBuilders ?: -1)
            )
        }
    }

    @Test
    fun `a goblin slot derives its total from the badge instead of a constant`() {
        val reading = BuilderDetection.detect(
            listOf(boxedLine("1/5", 1900, 1000, 1960, 1040)),
            2000,
            1080,
            goblinFace
        )

        assertEquals(4, reading.totalBuilders)
        assertEquals(4, reading.busyBuilders)
        assertEquals(0, reading.freeBuilders)
    }

    // ---- real-device ground truth, round 2 --------------------------------
    //
    // Six screenshots the user captured on the phone. Each one is a builder
    // strip the panel read wrongly (or, for c and f, correctly). The geometry is
    // the real 1360x612 strip: the builder slot's badge sits at
    // (1200,460)-(1230,480) in the village HUD and at (1000,548)-(1035,570) in
    // the building list.

    private fun village(badge: String) = boxedLine(badge, 1200, 460, 1230, 480)
    private fun lab(badge: String) = boxedLine(badge, 1250, 460, 1280, 480)
    private fun listSlot1(badge: String) = boxedLine(badge, 1000, 548, 1035, 570)

    @Test
    fun `case a five of five builders with a regular face and a laboratory beside it`() {
        // Panel said 0/4 free, 4 busy, Goblin offer yes.
        val reading = BuilderDetection.detect(
            listOf(village("5/5"), lab("1/1")),
            1360,
            612,
            regularFace
        )

        assertEquals(5, reading.freeBuilders)
        assertEquals(5, reading.totalBuilders)
        assertEquals(0, reading.busyBuilders)
        assertFalse(reading.goblinBuilderOffer)
        assertEquals("Builders: 5/5 free · 0 busy · Goblin offer: no", reading.panelLine())
    }

    @Test
    fun `case b two of five with a regular face and a goblin laboratory`() {
        val reading = BuilderDetection.detect(
            listOf(village("2/5"), lab("1/2")),
            1360,
            612,
            { box -> if (box.centerX > 1240) BuilderFaceKind.GOBLIN else BuilderFaceKind.REGULAR }
        )

        assertEquals(2, reading.freeBuilders)
        assertEquals(5, reading.totalBuilders)
        assertEquals(3, reading.busyBuilders)
        assertFalse(reading.goblinBuilderOffer)
        assertEquals("Builders: 2/5 free · 3 busy · Goblin offer: no", reading.panelLine())
    }

    @Test
    fun `case c a goblin builder slot beside a goblin laboratory`() {
        // This one was already correct; it must stay correct.
        val reading = BuilderDetection.detect(
            listOf(village("1/6"), lab("1/2")),
            1360,
            612,
            goblinFace
        )

        assertEquals(0, reading.freeBuilders)
        assertEquals(5, reading.totalBuilders)
        assertEquals(5, reading.busyBuilders)
        assertTrue(reading.goblinBuilderOffer)
        assertEquals("Builders: 0/5 free · 5 busy · Goblin offer: yes", reading.panelLine())
    }

    @Test
    fun `case d a banner over the strip does not hide the builder fraction`() {
        // Panel said "uncertain" while the strip plainly showed 5/5.
        val reading = BuilderDetection.detect(
            listOf(
                line("Gems in the Tree"),
                village("5/5"),
                lab("1/1")
            ),
            1360,
            612,
            regularFace
        )

        assertEquals(5, reading.freeBuilders)
        assertEquals(5, reading.totalBuilders)
        assertEquals(0, reading.busyBuilders)
        assertFalse(reading.goblinBuilderOffer)
    }

    @Test
    fun `case e the list view's first slot is the builder count`() {
        // The full-frame OCR missed the left-most badge and reported the lab's
        // 1/1 as the builder count.
        val reading = BuilderDetection.detect(
            listOf(listSlot1("5/5"), boxedLine("1/1", 1060, 548, 1090, 570)),
            1360,
            612,
            regularFace
        )

        assertEquals(5, reading.freeBuilders)
        assertEquals(5, reading.totalBuilders)
        assertEquals(0, reading.busyBuilders)
    }

    @Test
    fun `case f the list view's two of five is read as such`() {
        val reading = BuilderDetection.detect(
            listOf(listSlot1("2/5"), boxedLine("1/1", 1060, 548, 1090, 570)),
            1360,
            612,
            regularFace
        )

        assertEquals(2, reading.freeBuilders)
        assertEquals(5, reading.totalBuilders)
        assertEquals(3, reading.busyBuilders)
    }

    // ---- uncertainty, agreement and the panel line ------------------------

    @Test
    fun `a decisive reading is marked decisive and an uncertain one is not`() {
        val decisive = BuilderDetection.detect(listOf(village("5/5")), 1360, 612, regularFace)
        val uncertain = BuilderDetection.detect(listOf(village("5/5")), 1360, 612, { null })

        assertTrue(decisive.isDecisive)
        assertFalse(uncertain.isDecisive)
    }

    @Test
    fun `two agreeing readings outvote one different reading`() {
        val a = BuilderDetection.detect(listOf(village("5/5")), 1360, 612, regularFace)
        val b = BuilderDetection.detect(listOf(village("5/5")), 1360, 612, regularFace)
        val c = BuilderDetection.detect(listOf(village("2/5")), 1360, 612, regularFace)

        val merged = BuilderDetection.majority(listOf(a, b, c))

        assertEquals(5, merged.freeBuilders)
        assertEquals(5, merged.totalBuilders)
    }

    @Test
    fun `three different readings never become a guess`() {
        val readings = listOf(
            BuilderDetection.detect(listOf(village("5/5")), 1360, 612, regularFace),
            BuilderDetection.detect(listOf(village("2/5")), 1360, 612, regularFace),
            BuilderDetection.detect(listOf(village("1/6")), 1360, 612, goblinFace)
        )

        val merged = BuilderDetection.majority(readings)

        assertEquals(BuilderDetection.Status.UNCERTAIN, merged.status)
        assertNull(merged.freeBuilders)
        assertNull(merged.totalBuilders)
        assertFalse(merged.isDecisive)
        assertTrue(merged.panelLine().startsWith("Builders: uncertain"))
    }

    @Test
    fun `a lone decisive reading is not a majority`() {
        val only = BuilderDetection.detect(listOf(village("5/5")), 1360, 612, regularFace)

        val merged = BuilderDetection.majority(listOf(only))

        // One sample is returned unchanged; it is the caller's job to capture
        // again. What matters is that a single sample is never *called* a
        // majority of several.
        assertEquals(only.freeBuilders, merged.freeBuilders)
        assertEquals(1, listOf(only).size)
    }

    @Test
    fun `the panel line has exactly one shape for numbers and one for uncertainty`() {
        assertEquals(
            "Builders: 5/5 free · 0 busy · Goblin offer: no",
            BuilderDetection.panelLine(true, 5, 5, 0, goblinOffer = false, goblinKnown = true)
        )
        assertEquals(
            "Builders: 0/5 free · 5 busy · Goblin offer: yes",
            BuilderDetection.panelLine(true, 0, 5, 5, goblinOffer = true, goblinKnown = true)
        )
        assertEquals(
            "Builders: uncertain · Goblin offer: unknown",
            BuilderDetection.panelLine(false, null, null, null, goblinOffer = false, goblinKnown = false)
        )
        // Busy is derived when the reading did not state it.
        assertEquals(
            "Builders: 2/5 free · 3 busy · Goblin offer: no",
            BuilderDetection.panelLine(true, 2, 5, null, goblinOffer = false, goblinKnown = true)
        )
    }
}
