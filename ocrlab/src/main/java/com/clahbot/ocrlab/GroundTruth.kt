package com.clahbot.ocrlab

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import java.io.File

data class GroundTruth(
    val result: String,
    val stars: Int?,
    val damagePercent: Int?,
    val gold: Long?,
    val elixir: Long?,
    val darkElixir: Long?,
    val bonusGold: Long?,
    val bonusElixir: Long?,
    val bonusDarkElixir: Long?,
    val balanceGold: Long?,
    val balanceElixir: Long?,
    val balanceDarkElixir: Long?
) {
    companion object {
        fun load(file: File): Map<String, GroundTruth> {
            val root = JsonParser.parseString(file.readText()).asJsonObject
            val screens = root.getAsJsonObject("results_screens")
                ?: return emptyMap()
            val result = linkedMapOf<String, GroundTruth>()
            for ((name, element) in screens.entrySet()) {
                if (!element.isJsonObject) continue
                val screen = element.asJsonObject
                val loot = screen.objectOrNull("loot")
                val bonus = screen.objectOrNull("bonus")
                val balances = screen.objectOrNull("balances_shown")
                result[name] = GroundTruth(
                    result = screen.stringOrNull("result") ?: continue,
                    stars = screen.intOrNull("stars"),
                    damagePercent = screen.intOrNull("damage_percent"),
                    gold = loot?.longOrNull("gold"),
                    elixir = loot?.longOrNull("elixir"),
                    darkElixir = loot?.longOrNull("dark"),
                    bonusGold = bonus?.longOrNull("gold"),
                    bonusElixir = bonus?.longOrNull("elixir"),
                    bonusDarkElixir = bonus?.longOrNull("dark"),
                    balanceGold = balances?.longOrNull("gold"),
                    balanceElixir = balances?.longOrNull("elixir"),
                    balanceDarkElixir = balances?.longOrNull("dark")
                )
            }
            return result
        }

        private fun JsonObject.objectOrNull(key: String): JsonObject? =
            get(key)?.takeIf { it.isJsonObject }?.asJsonObject

        private fun JsonObject.stringOrNull(key: String): String? =
            get(key)?.takeIf { it.isJsonPrimitive }?.asString

        private fun JsonObject.intOrNull(key: String): Int? =
            get(key)?.takeIf { it.isJsonPrimitive }?.asInt

        private fun JsonObject.longOrNull(key: String): Long? =
            get(key)?.takeIf { it.isJsonPrimitive }?.asLong
    }
}
