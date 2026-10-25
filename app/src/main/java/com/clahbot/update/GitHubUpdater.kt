package com.clahbot.update

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.util.Log
import androidx.core.content.FileProvider
import com.clahbot.BuildConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest

object GitHubUpdater {

    private const val TAG = "AutoClashUpdate"
    private const val API_URL =
        "https://api.github.com/repos/mir-majid01/clahbot/releases/latest"
    private const val MAX_JSON_BYTES = 2_000_000L
    private const val MAX_CHECKSUM_BYTES = 8_192L
    private const val MAX_APK_BYTES = 200L * 1024L * 1024L

    sealed interface CheckResult {
        data object UpToDate : CheckResult
        data class Available(val release: UpdateRelease) : CheckResult
        data class Failure(val message: String) : CheckResult
    }

    sealed interface DownloadResult {
        data class Ready(val file: File) : DownloadResult
        data class Failure(val message: String) : DownloadResult
    }

    sealed interface InstallResult {
        data object Started : InstallResult
        data class NeedsPermission(val message: String) : InstallResult
        data class Failure(val message: String) : InstallResult
    }

    suspend fun check(): CheckResult = withContext(Dispatchers.IO) {
        var connection: HttpURLConnection? = null
        try {
            val conn = open(API_URL)
            connection = conn
            val code = conn.responseCode
            when {
                code == 404 -> CheckResult.Failure(
                    "No public release found. The repository or release may still be private."
                )

                code == 403 -> CheckResult.Failure(
                    "GitHub rate limit reached. Try again later."
                )

                code !in 200..299 -> CheckResult.Failure("GitHub returned HTTP $code.")
                else -> {
                    val text = conn.inputStream.bufferedReader()
                        .use { it.readLimited(MAX_JSON_BYTES) }
                    val release = GitHubReleaseParser.parse(text)
                    if (release == null) {
                        CheckResult.Failure("The latest release has no usable APK.")
                    } else if (release.isNewerThan(BuildConfig.VERSION_CODE)) {
                        CheckResult.Available(release)
                    } else {
                        CheckResult.UpToDate
                    }
                }
            }
        } catch (t: Throwable) {
            Log.w(TAG, "Update check failed", t)
            CheckResult.Failure(
                "Could not reach GitHub: ${t.message ?: "network error"}"
            )
        } finally {
            connection?.disconnect()
        }
    }

    suspend fun download(
        context: Context,
        release: UpdateRelease
    ): DownloadResult = withContext(Dispatchers.IO) {
        var connection: HttpURLConnection? = null
        val directory = File(context.cacheDir, "updates").apply { mkdirs() }
        val safeName = release.apk.name
            .replace(Regex("[^A-Za-z0-9._-]"), "_")
            .ifBlank { "AutoClash-update.apk" }
        val destination = File(directory, safeName)
        val temporary = File(directory, "$safeName.part")
        try {
            val expected = UpdateChecksum.fromDigest(release.apk.digest)
                ?: release.checksum?.let { asset ->
                    val text = fetchText(asset.url, MAX_CHECKSUM_BYTES)
                    UpdateChecksum.fromText(text.orEmpty())
                }
            if (expected == null) {
                return@withContext DownloadResult.Failure(
                    "The release has no SHA-256 checksum."
                )
            }

            val conn = open(release.apk.url)
            connection = conn
            val code = conn.responseCode
            if (code !in 200..299) {
                return@withContext DownloadResult.Failure(
                    "Download failed with HTTP $code."
                )
            }
            val announcedSize = conn.contentLengthLong
            if (announcedSize > MAX_APK_BYTES) {
                return@withContext DownloadResult.Failure("The update APK is too large.")
            }

            val digest = MessageDigest.getInstance("SHA-256")
            var total = 0L
            temporary.delete()
            conn.inputStream.use { input ->
                temporary.outputStream().use { output ->
                    val buffer = ByteArray(16 * 1024)
                    while (true) {
                        val read = input.read(buffer)
                        if (read < 0) break
                        total += read
                        if (total > MAX_APK_BYTES) {
                            throw IllegalStateException("The update APK is too large.")
                        }
                        digest.update(buffer, 0, read)
                        output.write(buffer, 0, read)
                    }
                }
            }
            if (release.apk.size > 0 && total != release.apk.size) {
                temporary.delete()
                return@withContext DownloadResult.Failure(
                    "Downloaded size does not match the release."
                )
            }
            val actual = digest.digest().joinToString("") {
                "%02x".format(it.toInt() and 0xff)
            }
            if (!actual.equals(expected, ignoreCase = true)) {
                temporary.delete()
                return@withContext DownloadResult.Failure(
                    "Checksum mismatch. The update was not installed."
                )
            }
            if (destination.exists()) destination.delete()
            if (!temporary.renameTo(destination)) {
                temporary.copyTo(destination, overwrite = true)
                temporary.delete()
            }
            DownloadResult.Ready(destination)
        } catch (t: Throwable) {
            temporary.delete()
            Log.w(TAG, "Update download failed", t)
            DownloadResult.Failure(
                "Could not download the update: ${t.message ?: "network error"}"
            )
        } finally {
            connection?.disconnect()
        }
    }

    fun launchInstaller(context: Context, file: File): InstallResult {
        if (!file.exists() || file.length() <= 0L) {
            return InstallResult.Failure("The downloaded update file is missing.")
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O &&
            !context.packageManager.canRequestPackageInstalls()
        ) {
            val intent = Intent(
                Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                Uri.parse("package:${context.packageName}")
            ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            return try {
                context.startActivity(intent)
                InstallResult.NeedsPermission(
                    "Allow AutoClash to install unknown apps, then tap Install again."
                )
            } catch (t: Throwable) {
                InstallResult.Failure("Could not open the install permission screen.")
            }
        }
        return try {
            val uri = FileProvider.getUriForFile(
                context,
                "${context.packageName}.updates",
                file
            )
            val intent = Intent(Intent.ACTION_VIEW)
                .setDataAndType(uri, "application/vnd.android.package-archive")
                .addFlags(
                    Intent.FLAG_ACTIVITY_NEW_TASK or
                        Intent.FLAG_GRANT_READ_URI_PERMISSION
                )
            context.startActivity(intent)
            InstallResult.Started
        } catch (t: Throwable) {
            Log.w(TAG, "Could not launch installer", t)
            InstallResult.Failure(
                "Could not open the Android installer: ${t.message ?: "no installer"}"
            )
        }
    }

    private fun open(url: String): HttpURLConnection {
        return (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = 12_000
            readTimeout = 20_000
            instanceFollowRedirects = true
            setRequestProperty("Accept", "application/vnd.github+json")
            setRequestProperty("User-Agent", "AutoClash/${BuildConfig.VERSION_NAME}")
        }
    }

    private fun fetchText(url: String, limit: Long): String? {
        var connection: HttpURLConnection? = null
        return try {
            val conn = open(url)
            connection = conn
            if (conn.responseCode !in 200..299) return null
            conn.inputStream.bufferedReader().use { it.readLimited(limit) }
        } catch (_: Throwable) {
            null
        } finally {
            connection?.disconnect()
        }
    }

    private fun java.io.Reader.readLimited(limit: Long): String {
        val builder = StringBuilder()
        val buffer = CharArray(4096)
        while (true) {
            val read = read(buffer)
            if (read < 0) break
            builder.append(buffer, 0, read)
            if (builder.length.toLong() > limit) break
        }
        return builder.toString()
    }
}
