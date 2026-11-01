package com.clahbot.util

/** Count-aware English wording so no surface says "1 attacks". */
object Plural {

    fun of(count: Int, singular: String, plural: String = singular + "s"): String =
        "$count ${if (count == 1) singular else plural}"

    fun attacks(count: Int): String = of(count, "attack")

    fun macros(count: Int): String = of(count, "macro")

    fun sessions(count: Int): String = of(count, "session")

    fun needReview(count: Int): String =
        "$count ${if (count == 1) "attack needs" else "attacks need"} review"
}
