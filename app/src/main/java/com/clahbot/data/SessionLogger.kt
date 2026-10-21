package com.clahbot.data

import com.clahbot.data.db.SessionLogEntity
import com.clahbot.data.models.AutomationMode
import com.clahbot.data.models.SessionStats

object SessionLogger {

    suspend fun logSession(
        context: android.content.Context,
        mode: AutomationMode,
        stats: SessionStats
    ) {
        val db = AppDatabaseProvider.get(context)
        val now = System.currentTimeMillis()
        db.sessionLogDao().insert(
            SessionLogEntity(
                mode = mode.name,
                durationMs = now - stats.sessionStartMs,
                resourcesCollected = stats.resourcesCollected,
                attacksLaunched = stats.attacksLaunched,
                startedAt = stats.sessionStartMs,
                endedAt = now
            )
        )
    }
}
