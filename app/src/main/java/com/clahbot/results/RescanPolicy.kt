package com.clahbot.results

import com.clahbot.ocr.ResultsOutcome

object RescanPolicy {
    fun mayApply(
        outcome: ResultsOutcome?,
        gold: Long?,
        elixir: Long?,
        darkElixir: Long?
    ): Boolean = outcome != null && gold != null && elixir != null && darkElixir != null
}
