package com.clahbot.walls

/**
 * The dedicated Auto Walls 2.0 safety vocabulary (PRD §10). It names every
 * control the 2.0 engine must never press — gem purchases, "buy missing
 * resources", wall rings and other premium-currency actions — plus the
 * short-of-resources wording. This is separate from the existing macro-path
 * guards, so 2.0 is strict without weakening them.
 */
object AutoWallsSafety {

    private val PREMIUM_TOKENS = listOf(
        "buy", "buythemissing", "gem", "gems", "wallring", "wallrings",
        "surrender", "endbattle", "charge", "topup", "premium", "moregems"
    )

    /** True when this OCR line names a premium / purchase control. */
    fun isPremiumControl(text: String): Boolean {
        val t = normalize(text)
        if (t.isEmpty()) return false
        return PREMIUM_TOKENS.any { t.contains(it) }
    }

    /** True when this line says a resource is short ("You Need More Gold", "Buy the missing …"). */
    fun isInsufficientResources(text: String): Boolean {
        val t = normalize(text)
        return t.contains("needmore") || t.contains("notenough") || t.contains("buythemissing") ||
            (t.contains("missing") && (t.contains("gold") || t.contains("elixir")))
    }

    fun normalize(text: String): String = text.lowercase().filter { it.isLetterOrDigit() }
}
