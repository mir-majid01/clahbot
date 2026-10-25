package com.clahbot.update

import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParser

data class UpdateAsset(
    val name: String,
    val url: String,
    val size: Long,
    val digest: String? = null,
    val id: Long? = null
)

data class UpdateRelease(
    val versionCode: Int,
    val tag: String,
    val title: String,
    val notes: String,
    val htmlUrl: String,
    val apk: UpdateAsset,
    val checksum: UpdateAsset?
) {
    fun isNewerThan(currentVersionCode: Int): Boolean = versionCode > currentVersionCode
}

object UpdateVersion {
    fun parseTag(tag: String): Int? {
        val match = Regex("""^[vV]?(\d+)""").find(tag.trim()) ?: return null
        return match.groupValues[1].toIntOrNull()?.takeIf { it > 0 }
    }
}

object UpdateChecksum {
    private val HEX64 = Regex("(?i)(?<![0-9a-f])[0-9a-f]{64}(?![0-9a-f])")

    fun fromText(text: String): String? =
        HEX64.find(text)?.value?.lowercase()

    fun fromDigest(digest: String?): String? {
        val value = digest?.trim()?.lowercase()?.removePrefix("sha256:")?.trim()
        return value?.takeIf { it.matches(Regex("[0-9a-f]{64}")) }
    }
}

object GitHubReleaseParser {
    fun parse(json: String): UpdateRelease? {
        return try {
            val root = JsonParser.parseString(json).asJsonObject
            val tag = root.string("tag_name") ?: return null
            val versionCode = UpdateVersion.parseTag(tag) ?: return null
            val assets = root.getAsJsonArray("assets") ?: return null
            val parsedAssets = assets.mapNotNull { element ->
                val asset = element.asJsonObject
                val name = asset.string("name") ?: return@mapNotNull null
                val url = asset.string("browser_download_url") ?: return@mapNotNull null
                UpdateAsset(
                    name = name,
                    url = url,
                    size = asset.get("size")?.takeIf { it.isJsonPrimitive }?.asLong ?: 0L,
                    digest = asset.string("digest"),
                    id = runCatching { asset.get("id")?.asLong }.getOrNull()
                )
            }
            val apk = parsedAssets.firstOrNull {
                it.name.endsWith(".apk", ignoreCase = true)
            } ?: return null
            val checksum = parsedAssets.firstOrNull {
                it.name.equals(apk.name + ".sha256", ignoreCase = true)
            } ?: parsedAssets.firstOrNull {
                it.name.endsWith(".sha256", ignoreCase = true)
            }
            UpdateRelease(
                versionCode = versionCode,
                tag = tag,
                title = root.string("name") ?: tag,
                notes = root.string("body").orEmpty(),
                htmlUrl = root.string("html_url").orEmpty(),
                apk = apk,
                checksum = checksum
            )
        } catch (_: Exception) {
            null
        }
    }

    private fun JsonObject.string(key: String): String? {
        val element: JsonElement = get(key) ?: return null
        if (element.isJsonNull || !element.isJsonPrimitive) return null
        return try {
            element.asString
        } catch (_: Exception) {
            null
        }
    }
}
