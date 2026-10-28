package com.clahbot.results

object CaptureRetention {

    data class Entry(
        val base: String,
        val files: List<String>,
        val bytes: Long,
        val modified: Long
    )

    /**
     * Oldest capture triples are removed first. The newest capture is always
     * kept, even when it alone is over the limit, so a save never deletes what
     * it just wrote.
     */
    fun selectForDeletion(
        entries: List<Entry>,
        maxCaptures: Int,
        maxBytes: Long,
        keepBase: String? = null
    ): Set<String> {
        if (entries.isEmpty()) return emptySet()
        val ordered = entries.sortedBy { it.modified }
        var count = ordered.size
        var bytes = ordered.sumOf { it.bytes }
        val deleted = linkedSetOf<String>()
        for (entry in ordered) {
            if (count <= maxCaptures && bytes <= maxBytes) break
            if (entry.base == keepBase) continue
            if (deleted.size >= ordered.size - 1) break
            deleted += entry.base
            count--
            bytes -= entry.bytes
        }
        return deleted
    }
}
