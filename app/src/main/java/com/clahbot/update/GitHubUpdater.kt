package com.clahbot.update

import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.core.content.FileProvider
import com.clahbot.BuildConfig
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.withContext
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.TimeUnit
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response

object GitHubUpdater {

    private const val TAG = "AutoClashUpdate"
    private const val REPO = "mir-majid01/clahbot"
    private const val API_URL = "https://api.github.com/repos/$REPO/releases/latest"
    private const val ATOM_URL = "https://github.com/$REPO/releases.atom"
    private const val ASSET_API = "https://api.github.com/repos/$REPO/releases/assets"
    private const val MAX_JSON_BYTES = 2_000_000L
    private const val MAX_CHECKSUM_BYTES = 8_192L
    private const val MAX_APK_BYTES = 200L * 1024L * 1024L
    private val downloadMutex = Mutex()
    private val client = OkHttpClient.Builder()
        .connectTimeout(12, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .build()

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
        data class Failure(val message: String) : InstallResult
    }

    suspend fun check(): CheckResult = withContext(Dispatchers.IO) {
        val atom = fetchText(ATOM_URL, MAX_JSON_BYTES, "application/atom+xml")
        val fromFeed = atom?.let { GitHubReleaseParser.parseAtom(it, REPO) }
        if (fromFeed != null) {
            return@withContext if (fromFeed.isNewerThan(BuildConfig.VERSION_CODE)) {
                CheckResult.Available(fromFeed)
            } else {
                CheckResult.UpToDate
            }
        }
        checkApi()
    }

    private suspend fun checkApi(): CheckResult = try {
        connected(API_URL, "application/vnd.github+json") { response ->
            val code = response.code
            when {
                code == 404 -> CheckResult.Failure(
                    "No public release found. The repository or release may still be private."
                )

                code == 403 -> CheckResult.Failure(
                    "GitHub is busy right now. Try again in a minute."
                )

                code !in 200..299 -> CheckResult.Failure("GitHub returned HTTP $code.")
                else -> {
                    val text = requireNotNull(response.body).charStream()
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
        }
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (error: Exception) {
        currentCoroutineContext().ensureActive()
        Log.w(TAG, "Update check failed", error)
        CheckResult.Failure("Could not reach GitHub: ${error.message ?: "network error"}")
    }

    suspend fun download(
        context: Context,
        release: UpdateRelease,
        onProgress: suspend (UpdateDownloadProgress) -> Unit = {}
    ): DownloadResult = withContext(Dispatchers.IO) {
        if (!downloadMutex.tryLock()) {
            return@withContext DownloadResult.Failure("An update download is already active.")
        }
        val directory = File(context.cacheDir, "updates")
        val safeName = release.apk.name.replace(Regex("[^A-Za-z0-9._-]"), "_")
        val destination = File(directory, safeName)
        val temporary = File(directory, "$safeName.part")
        try {
            require(safeName.endsWith(".apk", ignoreCase = true)) { "Invalid update APK name." }
            require(release.apk.size <= MAX_APK_BYTES) { "The update APK is too large." }
            check(directory.isDirectory || directory.mkdirs()) { "Cannot create update directory." }
            onProgress(UpdateDownloadProgress(UpdateDownloadStage.CONNECTING))
            val expected = UpdateChecksum.fromDigest(release.apk.digest)
                ?: release.checksum?.let { asset ->
                    val text = fetchText(asset.url, MAX_CHECKSUM_BYTES)
                    UpdateChecksum.fromText(text.orEmpty())
                }
            if (expected == null) {
                return@withContext DownloadResult.Failure("The release has no SHA-256 checksum.")
            }

            val urls = buildList {
                add(release.apk.url)
                release.apk.id?.let { add("$ASSET_API/$it") }
            }.distinct()
            var failure: String? = null
            for (url in urls) {
                currentCoroutineContext().ensureActive()
                onProgress(UpdateDownloadProgress(UpdateDownloadStage.CONNECTING))
                when (val attempt = downloadTo(url, temporary, release.apk.size, onProgress)) {
                    is Attempt.Failed -> failure = attempt.message
                    is Attempt.Ok -> {
                        onProgress(attempt.progress.copy(stage = UpdateDownloadStage.VERIFYING))
                        currentCoroutineContext().ensureActive()
                        if (release.apk.size > 0 && attempt.progress.downloadedBytes != release.apk.size) {
                            failure = "Downloaded size does not match the release."
                        } else if (!attempt.sha256.equals(expected, ignoreCase = true)) {
                            failure = "Checksum mismatch. The update was not installed."
                        } else {
                            currentCoroutineContext().ensureActive()
                            Files.move(
                                temporary.toPath(), destination.toPath(),
                                StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING
                            )
                            onProgress(attempt.progress.copy(stage = UpdateDownloadStage.READY))
                            currentCoroutineContext().ensureActive()
                            return@withContext DownloadResult.Ready(destination)
                        }
                    }
                }
            }
            DownloadResult.Failure(failure ?: "Could not download the update.")
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            currentCoroutineContext().ensureActive()
            Log.w(TAG, "Update download failed", error)
            DownloadResult.Failure(
                "Could not download the update: ${error.message ?: "network error"}"
            )
        } finally {
            temporary.delete()
            downloadMutex.unlock()
        }
    }

    private sealed interface Attempt {
        data class Ok(val progress: UpdateDownloadProgress, val sha256: String) : Attempt
        data class Failed(val message: String) : Attempt
    }

    private suspend fun downloadTo(
        url: String,
        temporary: File,
        releaseSize: Long,
        onProgress: suspend (UpdateDownloadProgress) -> Unit
    ): Attempt = try {
        connected(url, "application/octet-stream") { response ->
            val code = response.code
            check(code in 200..299) { "Download failed with HTTP $code." }
            val body = requireNotNull(response.body) { "The update response is empty." }
            val announcedSize = body.contentLength().takeIf { it >= 0L }
            check(announcedSize == null || announcedSize <= MAX_APK_BYTES) { "The update APK is too large." }
            check(releaseSize <= 0L || announcedSize == null || announcedSize == releaseSize) {
                "Downloaded size does not match the release."
            }
            val expectedSize = announcedSize ?: releaseSize.takeIf { it > 0L }
            val started = System.nanoTime()
            var reportedAt = 0L
            val digest = MessageDigest.getInstance("SHA-256")
            var total = 0L
            fun progress(): UpdateDownloadProgress {
                val seconds = (System.nanoTime() - started).coerceAtLeast(1L) / 1_000_000_000.0
                return UpdateDownloadProgress(
                    UpdateDownloadStage.DOWNLOADING, total, expectedSize, (total / seconds).toLong()
                )
            }
            body.byteStream().use { input ->
                temporary.outputStream().use { output ->
                    val buffer = ByteArray(16 * 1024)
                    while (true) {
                        currentCoroutineContext().ensureActive()
                        val read = input.read(buffer)
                        currentCoroutineContext().ensureActive()
                        if (read < 0) break
                        if (read == 0) continue
                        total += read
                        check(total <= MAX_APK_BYTES) { "The update APK is too large." }
                        check(expectedSize == null || total <= expectedSize) { "The update exceeds its declared size." }
                        digest.update(buffer, 0, read)
                        output.write(buffer, 0, read)
                        val now = System.nanoTime()
                        if (reportedAt == 0L || now - reportedAt >= 150_000_000L) {
                            onProgress(progress())
                            reportedAt = now
                        }
                    }
                    output.fd.sync()
                }
            }
            check(total > 0L) { "The downloaded update is empty." }
            check(announcedSize == null || total == announcedSize) { "The update download was incomplete." }
            val progress = progress()
            onProgress(progress)
            Attempt.Ok(progress, digest.digest().joinToString("") { "%02x".format(it.toInt() and 0xff) })
        }
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (error: Exception) {
        currentCoroutineContext().ensureActive()
        Log.w(TAG, "Download attempt failed", error)
        Attempt.Failed("Could not download the update: ${error.message ?: "network error"}")
    }

    fun launchInstaller(context: Context, file: File): InstallResult {
        if (!file.exists() || file.length() <= 0L) {
            return InstallResult.Failure("The downloaded update file is missing.")
        }
        return try {
            if (!file.name.endsWith(".apk", ignoreCase = true) ||
                file.canonicalFile.parentFile != File(context.cacheDir, "updates").canonicalFile
            ) {
                return InstallResult.Failure("Only a verified update APK can be installed.")
            }
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

    private suspend fun fetchText(
        url: String,
        limit: Long,
        accept: String = "application/vnd.github+json"
    ): String? = try {
        connected(url, accept) { response ->
            if (!response.isSuccessful) null
            else response.body?.charStream()?.use { it.readLimited(limit) }
        }
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (_: Exception) {
        currentCoroutineContext().ensureActive()
        null
    }

    private suspend fun <T> connected(
        url: String,
        accept: String,
        block: suspend (Response) -> T
    ): T = coroutineScope {
        val request = Request.Builder().url(url)
            .header("Accept", accept)
            .header("Accept-Encoding", "identity")
            .header("User-Agent", "AutoClash/${BuildConfig.VERSION_NAME}")
            .build()
        val call = client.newCall(request)
        // Call.cancel closes the socket even while response-body reads are blocked.
        val closer = launch(Dispatchers.IO, start = CoroutineStart.UNDISPATCHED) {
            try {
                awaitCancellation()
            } finally {
                call.cancel()
            }
        }
        try {
            currentCoroutineContext().ensureActive()
            call.execute().use { block(it) }
        } finally {
            closer.cancel()
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
