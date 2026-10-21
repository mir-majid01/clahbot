package com.clahbot.data

import android.content.Context
import com.clahbot.data.db.BotSettingsDao
import com.clahbot.data.db.BotSettingsEntity
import com.clahbot.data.models.BotConfig
import com.clahbot.data.models.Macro
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken

class ConfigRepository(private val dao: BotSettingsDao) {

    private val gson = Gson()

    suspend fun loadConfig(): BotConfig {
        val json = dao.get(KEY_CONFIG)
        return if (json != null) {
            gson.fromJson(json, BotConfig::class.java)
        } else {
            BotConfig()
        }
    }

    suspend fun saveConfig(config: BotConfig) {
        dao.set(BotSettingsEntity(KEY_CONFIG, gson.toJson(config)))
    }

    suspend fun isOnboardingComplete(): Boolean {
        return dao.get(KEY_ONBOARDING) == "true"
    }

    suspend fun setOnboardingComplete() {
        dao.set(BotSettingsEntity(KEY_ONBOARDING, "true"))
    }

    suspend fun loadMacros(): List<Macro> {
        val json = dao.get(KEY_MACROS)
        if (json != null) {
            return gson.fromJson(json, object : TypeToken<List<Macro>>() {}.type)
        }
        return emptyList()
    }

    suspend fun saveMacro(macro: Macro) {
        val macros = loadMacros().toMutableList()
        val idx = macros.indexOfFirst { it.id == macro.id }
        if (idx >= 0) macros[idx] = macro else macros.add(macro)
        dao.set(BotSettingsEntity(KEY_MACROS, gson.toJson(macros)))
    }

    suspend fun deleteMacro(id: Long) {
        val macros = loadMacros().filter { it.id != id }
        dao.set(BotSettingsEntity(KEY_MACROS, gson.toJson(macros)))
    }

    suspend fun getNextMacroId(): Long {
        val macros = loadMacros()
        return (macros.maxOfOrNull { it.id } ?: 0) + 1
    }

    companion object {
        private const val KEY_CONFIG = "bot_config"
        private const val KEY_ONBOARDING = "onboarding_complete"
        private const val KEY_MACROS = "saved_macros"

        fun from(context: Context): ConfigRepository {
            return ConfigRepository(AppDatabaseProvider.get(context).botSettingsDao())
        }
    }
}

object AppDatabaseProvider {
    fun get(context: Context) = com.clahbot.data.db.AppDatabase.getInstance(context)
}
