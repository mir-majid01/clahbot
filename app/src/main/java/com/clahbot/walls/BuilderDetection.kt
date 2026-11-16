package com.clahbot.walls

import com.clahbot.ocr.BuilderFaceKind
import com.clahbot.ocr.OcrBox
import com.clahbot.ocr.OcrLine

/**
 * Pure builder-availability detector for the wall-upgrade pipeline.
 *
 * The real game shows builder availability as a `free/total` fraction below a
 * builder face in the bottom builder strip. That strip also shows the
 * laboratory and other building counters as fractions of their own, so reading
 * "any fraction at the bottom right" is not enough: on the device the left-most
 * builder slot read `1/7` with a goblin face and the laboratory beside it read
 * `1/2`, which the old detector could report as one free regular builder out of
 * two.
 *
 * This version therefore:
 *  - takes ONLY the left-most fraction slot of the strip (never a neighbour);
 *  - classifies the face above that slot and requires it: regular face →
 *    `free/total` regular builders, goblin face → zero free regular builders and
 *    `total - 1` regular builders (the goblin occupies one slot);
 *  - never hardcodes 5, 6 or 7 builders;
 *  - reports UNCERTAIN when the face cannot be classified, when the goblin
 *    arithmetic leaves an impossible regular total, or when the frame carries
 *    no usable fraction;
 *  - treats Goblin/Apprentice/BOB as flags that never count as regular builders.
 *
 * [Reading] remains uncertified: it never authorizes an upgrade by itself.
 */
object BuilderDetection {

    /** Readings at or above this confidence may authorize an upgrade (Stage 5C+). */
    const val MIN_CONFIDENCE_TO_AUTHORIZE = 0.70f

    /** Terminal-ish states for the regular builder availability question. */
    enum class Status { AVAILABLE, BUSY, NONE_AVAILABLE, UNCERTAIN }

    /**
     * Pure detection output. No provenance, no timestamp, no validation flag —
     * just what one captured frame suggests. This can never authorize an
     * upgrade on its own.
     */
    data class Reading(
        val status: Status,
        val freeBuilders: Int? = null,
        val totalBuilders: Int? = null,
        val busyBuilders: Int? = null,
        val goblinBuilderOffer: Boolean = false,
        /** True when the frame actually answered the Goblin-offer question. */
        val goblinOfferKnown: Boolean = false,
        val builderApprenticeHelper: Boolean = false,
        val bOBDetected: Boolean = false,
        val confidence: Float = 0f,
        /** Face of the left-most builder slot, when it could be classified. */
        val face: BuilderFaceKind? = null
    ) {
        /** Detection-only availability: true when the frame says a builder is free. */
        val isAvailable: Boolean
            get() = status == Status.AVAILABLE &&
                confidence >= MIN_CONFIDENCE_TO_AUTHORIZE &&
                (freeBuilders ?: 0) > 0

        /** True when this frame actually answered the builder question. */
        val isDecisive: Boolean
            get() = status != Status.UNCERTAIN && confidence >= MIN_CONFIDENCE_TO_AUTHORIZE

        /**
         * The floating-panel builder line, in exactly one format on every path:
         * `Builders: X/Y free · Z busy · Goblin offer: yes/no/unknown`.
         * When the frame did not answer, the numbers are replaced by
         * "uncertain" rather than filled with a guess.
         */
        fun panelLine(): String = BuilderDetection.panelLine(
            detected = status != Status.UNCERTAIN,
            free = freeBuilders,
            total = totalBuilders,
            busy = busyBuilders,
            goblinOffer = goblinBuilderOffer,
            goblinKnown = goblinOfferKnown
        )

        /** One-line status for diagnostics and the floating panel. */
        fun describe(): String {
            val base = when (status) {
                Status.AVAILABLE -> {
                    val free = freeBuilders ?: 0
                    val total = totalBuilders ?: free
                    val busy = busyBuilders ?: (total - free).coerceAtLeast(0)
                    "builders $free/$total free · $busy busy"
                }
                Status.BUSY -> "builders busy · no free regular builder confirmed"
                Status.NONE_AVAILABLE -> {
                    val total = totalBuilders
                    if (total != null) {
                        "builders 0/$total free · ${busyBuilders ?: total} busy"
                    } else {
                        "builders 0 free · all regular builders busy"
                    }
                }
                Status.UNCERTAIN -> "builders uncertain"
            }
            val flags = buildList {
                if (goblinBuilderOffer) add("Goblin Builder offer detected")
                if (builderApprenticeHelper) add("Builder's Apprentice seen")
                if (bOBDetected) add("BOB seen")
            }
            val extra = if (flags.isEmpty()) "" else " · " + flags.joinToString(" · ")
            return base + extra
        }
    }

    /**
     * Classifies the builder signals of one captured frame.
     *
     * @param frameWidth frame width when known; enables the bottom-strip
     *   geometry preference.
     * @param frameHeight frame height when known.
     * @param faceAt samples the face above a fraction box. When it is absent or
     *   returns null the frame cannot say which slot the fraction belongs to,
     *   so the reading is UNCERTAIN instead of guessing a regular builder.
     */
    fun detect(
        lines: List<OcrLine>,
        frameWidth: Int = 0,
        frameHeight: Int = 0,
        faceAt: ((OcrBox) -> BuilderFaceKind?)? = null
    ): Reading {
        val normalized = lines.map { normalize(it.text) }
        val goblinText = normalized.any { it.contains(GOBLIN) }
        val apprentice = normalized.any { it.contains(APPRENTICE) }
        val bob = normalized.any { it.contains(BOB) }

        val slot = findLeftmostSlot(lines, frameWidth, frameHeight)
        if (slot != null) {
            val face = slot.box?.let { faceAt?.invoke(it) }
            return when (face) {
                BuilderFaceKind.GOBLIN -> goblinSlot(slot, apprentice, bob)
                BuilderFaceKind.REGULAR -> regularSlot(slot, goblinText, apprentice, bob)
                null -> Reading(
                    status = Status.UNCERTAIN,
                    goblinBuilderOffer = goblinText,
                    goblinOfferKnown = false,
                    builderApprenticeHelper = apprentice,
                    bOBDetected = bob,
                    // A fraction exists but its slot cannot be identified.
                    confidence = 0.30f
                )
            }
        }

        // The Goblin Builder is a Gem-paid separate option. Its offer is never
        // an independently available regular builder, so the regular path stops
        // — but the offer itself is reported as a flag.
        if (goblinText && !apprentice && !bob) {
            return Reading(
                status = Status.NONE_AVAILABLE,
                goblinBuilderOffer = true,
                goblinOfferKnown = true,
                confidence = 0.90f
            )
        }

        // Assistant/BOB signals say nothing reliable about the regular builders.
        if (apprentice || bob) {
            return Reading(
                status = Status.UNCERTAIN,
                goblinBuilderOffer = goblinText,
                goblinOfferKnown = goblinText,
                builderApprenticeHelper = apprentice,
                bOBDetected = bob,
                confidence = 0.80f
            )
        }

        // No fraction, no face: the frame cannot answer the question. The old
        // "find a number next to the word Builder" fallback read the Builder's
        // Hut level as a free builder, so it is gone; fail closed instead.
        return Reading(status = Status.UNCERTAIN, confidence = 0f)
    }

    /** Separator used in the floating panel's builder line. */
    const val PANEL_SEPARATOR = "\u00B7"

    /**
     * The one builder line the floating panel shows. Both the persisted scan
     * report and the detector's own [Reading] go through this function, so the
     * panel can never show two different shapes for the same state.
     */
    fun panelLine(
        detected: Boolean,
        free: Int?,
        total: Int?,
        busy: Int?,
        goblinOffer: Boolean,
        goblinKnown: Boolean
    ): String {
        val offer = when {
            !goblinKnown -> "unknown"
            goblinOffer -> "yes"
            else -> "no"
        }
        if (!detected || free == null || total == null) {
            return "Builders: uncertain $PANEL_SEPARATOR Goblin offer: $offer"
        }
        val busyCount = busy ?: (total - free).coerceAtLeast(0)
        return "Builders: $free/$total free $PANEL_SEPARATOR $busyCount busy" +
            " $PANEL_SEPARATOR Goblin offer: $offer"
    }

    /**
     * Folds up to three readings of the same screen into one answer.
     *
     * A single frame can miss the badge or the face, so the scanner captures
     * again when the first reading is uncertain. Two agreeing decisive readings
     * outvote one, and a lone decisive reading is NOT treated as a majority —
     * the answer stays uncertain instead of guessing.
     */
    fun majority(readings: List<Reading>): Reading {
        if (readings.isEmpty()) return Reading(status = Status.UNCERTAIN)
        if (readings.size == 1) return readings.single()
        val decisive = readings.filter { it.isDecisive }
        if (decisive.size >= 2) {
            val votes = decisive.groupBy { it.answerKey() }
            val winner = votes.entries.maxByOrNull { it.value.size }
            if (winner != null && winner.value.size >= 2) {
                return winner.value.maxByOrNull { it.confidence } ?: winner.value.first()
            }
        }
        return readings.first().copy(
            status = Status.UNCERTAIN,
            freeBuilders = null,
            totalBuilders = null,
            busyBuilders = null,
            confidence = 0f
        )
    }

    /**
     * The answer a reading stands for, used for majority voting. Readings with
     * the same counts and the same goblin answer vote together; anything else
     * is a different answer even when both are confident.
     */
    private fun Reading.answerKey(): String =
        "${freeBuilders ?: "-"}/${totalBuilders ?: "-"}/$goblinBuilderOffer"

    /**
     * A regular-builder slot: the fraction is the regular free/total count.
     * `goblinText` is still reported as an offer flag because a nearby banner
     * may mention it even when this particular slot shows a regular face.
     */
    private fun regularSlot(
        slot: Slot,
        goblinText: Boolean,
        apprentice: Boolean,
        bob: Boolean
    ): Reading {
        val free = slot.free
        val total = slot.total
        val busy = (total - free).coerceAtLeast(0)
        return Reading(
            status = if (free > 0) Status.AVAILABLE else Status.NONE_AVAILABLE,
            freeBuilders = free,
            totalBuilders = total,
            busyBuilders = busy,
            goblinBuilderOffer = goblinText,
            // The slot face itself is visible, so the offer question is known:
            // a regular face means this slot is not the goblin builder.
            goblinOfferKnown = true,
            builderApprenticeHelper = apprentice,
            bOBDetected = bob,
            confidence = if (slot.inBuilderStrip) 0.90f else 0.65f,
            face = BuilderFaceKind.REGULAR
        )
    }

    /**
     * A goblin-builder slot: the goblin is offered and occupies one of the
     * slots the fraction counts, so the regular builders are `total - 1`.
     * The regular count never hardcodes a builder total; it is derived from the
     * fraction. `total - 1 < 2` is impossible for a real goblin slot (the game
     * starts with two regular builders), so such a read is a laboratory badge
     * or a misread and stays UNCERTAIN.
     */
    private fun goblinSlot(slot: Slot, apprentice: Boolean, bob: Boolean): Reading {
        val regularTotal = slot.total - 1
        if (regularTotal < MIN_GOBLIN_REGULAR_TOTAL) {
            return Reading(
                status = Status.UNCERTAIN,
                goblinBuilderOffer = true,
                goblinOfferKnown = true,
                builderApprenticeHelper = apprentice,
                bOBDetected = bob,
                confidence = 0.30f,
                face = BuilderFaceKind.GOBLIN
            )
        }
        return Reading(
            status = Status.NONE_AVAILABLE,
            freeBuilders = 0,
            totalBuilders = regularTotal,
            busyBuilders = regularTotal,
            goblinBuilderOffer = true,
            goblinOfferKnown = true,
            builderApprenticeHelper = apprentice,
            bOBDetected = bob,
            confidence = if (slot.inBuilderStrip) 0.90f else 0.65f,
            face = BuilderFaceKind.GOBLIN
        )
    }

    // ---- fraction slots -----------------------------------------------------

    private data class Slot(
        val free: Int,
        val total: Int,
        val box: OcrBox?,
        /** True when the badge sits in the bottom builder strip of the frame. */
        val inBuilderStrip: Boolean
    )

    /** `free/total`, allowing OCR slash variants and stray spaces. */
    private val FRACTION = Regex("(\\d{1,2})\\s*[/|\\\\]\\s*(\\d{1,2})")

    /**
     * The left-most fraction of the builder strip. Fractions are clustered by
     * horizontal position first, so a line that OCR merged (`1/7 1/2 0/1`) and
     * three separate lines produce the same answer: the left-most cluster wins,
     * and inside it the left-most badge is the regular/goblin builder slot.
     */
    private fun findLeftmostSlot(
        lines: List<OcrLine>,
        frameWidth: Int,
        frameHeight: Int
    ): Slot? {
        val found = ArrayList<Slot>()
        for (line in lines) {
            if (isBuildersHut(line.text)) continue
            val text = buildString {
                append(line.text)
                for (element in line.elements) {
                    append(' ')
                    append(element.text)
                }
            }
            val matches = FRACTION.findAll(text).toList()
            if (matches.isEmpty()) continue
            val elementBoxes = line.elements
                .filter { it.box != null && FRACTION.containsMatchIn(it.text) }
                .mapNotNull { it.box }
            matches.forEachIndexed { index, match ->
                val free = match.groupValues[1].toIntOrNull() ?: return@forEachIndexed
                val total = match.groupValues[2].toIntOrNull() ?: return@forEachIndexed
                if (total !in 1..MAX_BUILDERS) return@forEachIndexed
                if (free < 0 || free > total) return@forEachIndexed
                val box = if (elementBoxes.size == matches.size) {
                    elementBoxes[index]
                } else {
                    subBox(line.box, index, matches.size)
                }
                val inStrip = box != null &&
                    frameWidth > 0 && frameHeight > 0 &&
                    box.centerY >= frameHeight * BOTTOM_REGION &&
                    box.centerX >= frameWidth * RIGHT_REGION
                found += Slot(free, total, box, inStrip)
            }
        }
        if (found.isEmpty()) return null

        // Prefer badges in the real bottom strip; a fraction elsewhere is a
        // weaker signal but still the only one, so it may be used.
        val pool = found.filter { it.inBuilderStrip }.ifEmpty { found }
        // Prefer badges whose geometry is known: a box is needed to read the
        // face above the slot at all, so a box-less match is the last resort.
        val sorted = pool.sortedWith(
            compareBy(
                { it.box == null },
                { it.box?.centerX ?: 0 },
                { it.box?.centerY ?: 0 }
            )
        )
        val heights = sorted.mapNotNull { it.box?.height }.sorted()
        val medianHeight = if (heights.isEmpty()) 0 else heights[heights.size / 2]
        val tolerance = maxOf(28, (medianHeight * 1.5f).toInt())
        val first = sorted.first()
        val firstX = first.box?.centerX ?: 0
        return sorted.firstOrNull {
            ((it.box?.centerX ?: 0) - firstX) in 0..tolerance
        }
    }

    /**
     * Approximate box of one match inside a merged OCR line: the line's width is
     * split evenly between its matches. When the OCR elements carry their own
     * boxes those are used instead (handled by the caller).
     */
    private fun subBox(box: OcrBox?, index: Int, count: Int): OcrBox? {
        box ?: return null
        if (count <= 1) return box
        val slot = box.width / count
        val left = box.left + slot * index
        val right = if (index == count - 1) box.right else left + slot
        return OcrBox(left, box.top, right, box.bottom)
    }

    /** A builder-list building row ("Builder's Hut" / "Builder Hut"). */
    private fun isBuildersHut(text: String): Boolean {
        val normalized = normalize(text)
        return normalized.contains(BUILDER) && normalized.contains("hut")
    }

    private fun normalize(text: String): String = text.lowercase().filter { it.isLetterOrDigit() }

    private const val GOBLIN = "goblin"
    private const val APPRENTICE = "apprentice"
    private const val BOB = "bob"
    private const val BUILDER = "builder"

    /** Clash never has more than a handful of builders; larger totals are noise. */
    private const val MAX_BUILDERS = 10

    /**
     * A real goblin slot counts at least the two starting regular builders plus
     * the goblin itself. A smaller total means the badge is not that slot.
     */
    private const val MIN_GOBLIN_REGULAR_TOTAL = 2

    private const val BOTTOM_REGION = 0.66f
    private const val RIGHT_REGION = 0.55f
}
