package com.clahbot.triggers

import kotlin.math.abs
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TemplateMatcherTest {

    private fun frame(w: Int, h: Int, draw: (x: Int, y: Int) -> Int): TemplateMatcher.Frame {
        val px = IntArray(w * h)
        for (y in 0 until h) for (x in 0 until w) {
            val v = draw(x, y).coerceIn(0, 255)
            px[y * w + x] = (0xff shl 24) or (v shl 16) or (v shl 8) or v
        }
        return TemplateMatcher.Frame(px, w, h)
    }

    private fun pattern(x: Int, y: Int) = 20 + x * 9 + y * 5

    private fun template(size: Int = 12) = frame(size, size) { x, y -> pattern(x, y) }

    private fun singleCopy(ox: Int, oy: Int, size: Int = 12, shift: Int = 0): TemplateMatcher.Frame =
        frame(80, 60) { x, y ->
            if (x in ox until ox + size && y in oy until oy + size) {
                pattern(x - ox, y - oy) + shift
            } else {
                10 + shift
            }
        }

    @Test
    fun `finds an exact copy at an offset`() {
        val match = TemplateMatcher.match(
            singleCopy(ox = 25, oy = 18),
            template(),
            TemplateMatcher.Config(threshold = 0.9f, scales = listOf(1f), stride = 1)
        )
        assertNotNull("an exact copy inside the frame must be found", match)
        assertTrue("left ${match!!.left} should be near 25", abs(match.left - 25) <= 1)
        assertTrue("top ${match.top} should be near 18", abs(match.top - 18) <= 1)
        assertTrue("score ${match.score} should be high", match.score >= 0.9f)
    }

    @Test
    fun `a uniform brightness shift still matches`() {
        // Adding the same constant to every pixel preserves NCC, so a screen
        // captured at a different brightness must still be recognised.
        val match = TemplateMatcher.match(
            singleCopy(ox = 25, oy = 18, shift = 40),
            template(),
            TemplateMatcher.Config(threshold = 0.9f, scales = listOf(1f), stride = 1)
        )
        assertNotNull("a brightness-shifted copy must still match", match)
        assertTrue(abs(match!!.left - 25) <= 1)
        assertTrue(abs(match.top - 18) <= 1)
    }

    @Test
    fun `a multi-scale search still finds a native-size copy`() {
        val match = TemplateMatcher.match(
            singleCopy(ox = 30, oy = 20),
            template(),
            TemplateMatcher.Config(
                threshold = 0.9f,
                scales = listOf(0.85f, 1.0f, 1.15f),
                stride = 1
            )
        )
        assertNotNull("the multi-scale scan must keep the true match", match)
        assertTrue(abs(match!!.left - 30) <= 2)
        assertTrue(abs(match.top - 20) <= 2)
    }

    @Test
    fun `an unrelated image does not match`() {
        val live = frame(80, 60) { x, y -> 200 - x * 3 + y * 11 }
        val match = TemplateMatcher.match(
            live,
            template(),
            TemplateMatcher.Config(threshold = 0.9f, scales = listOf(1f), stride = 1)
        )
        assertNull("an unrelated image must not produce a match", match)
    }

    @Test
    fun `two identical copies are ambiguous and refuse to act`() {
        val live = frame(80, 60) { x, y ->
            val inFirst = x in 10 until 22 && y in 10 until 22
            val inSecond = x in 50 until 62 && y in 10 until 22
            when {
                inFirst -> pattern(x - 10, y - 10)
                inSecond -> pattern(x - 50, y - 10)
                else -> 10
            }
        }
        val match = TemplateMatcher.match(
            live,
            template(),
            TemplateMatcher.Config(threshold = 0.9f, ambiguityMargin = 0.1f, scales = listOf(1f), stride = 1)
        )
        assertNull("two equal copies must be treated as ambiguous", match)
    }
}
