package com.clahbot.update

import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParser

enum class UpdateDownloadStage { CONNECTING, DOWNLOADING, VERIFYING, READY }

data class UpdateDownloadProgress(
    val stage: UpdateDownloadStage,
    val downloadedBytes: Long = 0L,
    val totalBytes: Long? = null,
    val bytesPerSecond: Long = 0L
) {
    val fraction: Float?
        get() = totalBytes?.takeIf { it > 0L }?.let {
            (downloadedBytes.toDouble() / it).coerceIn(0.0, 1.0).toFloat()
        }
}

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

    /**
     * The Releases Atom feed is not part of the REST API and therefore is not
     * subject to the 60-requests/hour anonymous API limit. It gives the latest
     * tag and title; the release workflow always names the APK and its checksum
     * in a predictable way, so the download URLs can be built from that.
     */
    fun parseAtom(atom: String, repo: String): UpdateRelease? {
        return try {
            val entry = Regex("<entry\\b[^>]*>(.*?)</entry>", RegexOption.DOT_MATCHES_ALL)
                .find(atom)?.groupValues?.get(1) ?: return null
            val title = Regex("<title\\b[^>]*>(.*?)</title>", RegexOption.DOT_MATCHES_ALL)
                .find(entry)?.groupValues?.get(1)
                ?.replace(Regex("<!\\[CDATA\\[(.*?)]]>", RegexOption.DOT_MATCHES_ALL), "$1")
                ?.replace("&amp;", "&")
                ?.trim()
                ?.takeIf { it.isNotBlank() }
                ?: return null
            val link = Regex(
                "<link\\b[^>]*rel=\"alternate\"[^>]*href=\"([^\"]+)\"",
                RegexOption.DOT_MATCHES_ALL
            ).find(entry)?.groupValues?.get(1)
                ?: Regex(
                    "<link\\b[^>]*href=\"([^\"]+)\"[^>]*rel=\"alternate\"",
                    RegexOption.DOT_MATCHES_ALL
                ).find(entry)?.groupValues?.get(1)
                ?: return null
            val tag = link.trimEnd('/').substringAfterLast('/')
            val versionCode = UpdateVersion.parseTag(tag) ?: return null
            val versionName = title
                .removePrefix("AutoClash")
                .trim()
                .ifBlank { tag.removePrefix("v").removePrefix("V") }
            val apkName = "AutoClash-$versionName.apk"
            val base = "https://github.com/$repo/releases/download/$tag/"
            UpdateRelease(
                versionCode = versionCode,
                tag = tag,
                title = title,
                notes = "",
                htmlUrl = link,
                apk = UpdateAsset(
                    name = apkName,
                    url = base + apkName,
                    size = 0L
                ),
                checksum = UpdateAsset(
                    name = "$apkName.sha256",
                    url = base + "$apkName.sha256",
                    size = 0L
                )
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
