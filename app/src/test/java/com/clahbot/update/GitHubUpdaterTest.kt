package com.clahbot.update

import java.io.File
import java.net.ServerSocket
import java.security.MessageDigest
import java.util.UUID
import kotlin.concurrent.thread
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.launch
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class GitHubUpdaterTest {
    private val context = RuntimeEnvironment.getApplication()
    private val servers = mutableListOf<ServerSocket>()
    private val files = mutableListOf<File>()
    private val payload = ByteArray(40_000) { (it % 251).toByte() }

    @After
    fun cleanUp() {
        servers.forEach { it.close() }
        files.forEach { it.delete() }
    }

    @Test
    fun `truncated HTTP response cannot become an installable file`() = runBlocking {
        val release = release(contentLength = payload.size + 100)

        val result = GitHubUpdater.download(context, release)

        assertTrue(result.toString(), result is GitHubUpdater.DownloadResult.Failure)
        assertFalse(destination(release).exists())
        assertFalse(File(destination(release).path + ".part").exists())
    }

    @Test
    fun `checksum mismatch cannot become an installable file`() = runBlocking {
        val release = release().let { it.copy(apk = it.apk.copy(digest = "0".repeat(64))) }

        val result = GitHubUpdater.download(context, release)

        assertTrue(result.toString(), result is GitHubUpdater.DownloadResult.Failure)
        assertFalse(destination(release).exists())
    }

    @Test
    fun `complete matching response is published with the exact bytes`() = runBlocking {
        val release = release()

        val result = GitHubUpdater.download(context, release)

        assertTrue(result.toString(), result is GitHubUpdater.DownloadResult.Ready)
        assertTrue(payload.contentEquals(destination(release).readBytes()))
        assertEquals(40_000L, destination(release).length())
        assertFalse(File(destination(release).path + ".part").exists())
    }

    @Test
    fun `progress reports bytes received before verification and ready`() = runBlocking {
        val release = release()
        val events = mutableListOf<UpdateDownloadProgress>()

        val result = GitHubUpdater.download(context, release) { events += it }

        assertTrue(result is GitHubUpdater.DownloadResult.Ready)
        val receiving = events.filter { it.stage == UpdateDownloadStage.DOWNLOADING }
        assertTrue(receiving.any { it.downloadedBytes in 1L until 40_000L })
        assertEquals(40_000L, receiving.last().downloadedBytes)
        assertEquals(40_000L, receiving.last().totalBytes)
        assertTrue(receiving.last().bytesPerSecond > 0)
        assertEquals(UpdateDownloadStage.VERIFYING, events[events.lastIndex - 1].stage)
        assertEquals(UpdateDownloadStage.READY, events.last().stage)
    }

    @Test
    fun `unknown length never invents a percentage`() = runBlocking {
        val release = release(contentLength = null)
        val events = mutableListOf<UpdateDownloadProgress>()

        val result = GitHubUpdater.download(context, release) { events += it }

        assertTrue(result is GitHubUpdater.DownloadResult.Ready)
        val receiving = events.filter { it.stage == UpdateDownloadStage.DOWNLOADING }
        assertTrue(receiving.isNotEmpty())
        assertTrue(receiving.all { it.fraction == null && it.totalBytes == null })
        assertEquals(40_000L, receiving.last().downloadedBytes)
    }

    @Test
    fun `cancelling from a progress callback never publishes the partial APK`() = runBlocking {
        val release = release()
        var cancelled = false
        try {
            GitHubUpdater.download(context, release) {
                if (it.stage == UpdateDownloadStage.DOWNLOADING && it.downloadedBytes > 0) {
                    throw kotlinx.coroutines.CancellationException("test cancellation")
                }
            }
        } catch (_: kotlinx.coroutines.CancellationException) {
            cancelled = true
        }

        assertTrue("Cancellation must propagate, not become success or failure", cancelled)
        assertFalse(destination(release).exists())
        assertFalse(File(destination(release).path + ".part").exists())
    }

    @Test
    fun `release size mismatch is refused even without an HTTP length`() = runBlocking {
        val release = release(contentLength = null).let { it.copy(apk = it.apk.copy(size = 41_000)) }
        assertTrue(GitHubUpdater.download(context, release) is GitHubUpdater.DownloadResult.Failure)
        assertFalse(destination(release).exists())
    }

    @Test
    fun `cancellation during verification prevents publication`() = runBlocking {
        val release = release()
        var cancelled = false
        try {
            GitHubUpdater.download(context, release) {
                if (it.stage == UpdateDownloadStage.VERIFYING) {
                    throw kotlinx.coroutines.CancellationException("cancel verification")
                }
            }
        } catch (_: kotlinx.coroutines.CancellationException) {
            cancelled = true
        }
        assertTrue(cancelled)
        assertFalse(destination(release).exists())
        assertFalse(File(destination(release).path + ".part").exists())
        assertTrue(GitHubUpdater.download(context, release()) is GitHubUpdater.DownloadResult.Ready)
    }

    @Test
    fun `a second download cannot overwrite an active partial file`() = runBlocking {
        val release = release()
        var second: GitHubUpdater.DownloadResult? = null
        val result = GitHubUpdater.download(context, release) {
            if (it.stage == UpdateDownloadStage.DOWNLOADING && second == null) {
                second = GitHubUpdater.download(context, release)
            }
        }
        assertTrue(second is GitHubUpdater.DownloadResult.Failure)
        assertTrue(result is GitHubUpdater.DownloadResult.Ready)
        assertTrue(payload.contentEquals(destination(release).readBytes()))
    }

    @Test
    fun `a partial file cannot be passed to the Android installer`() {
        val partial = File(context.cacheDir, "updates/test-${UUID.randomUUID()}.apk.part")
        partial.parentFile!!.mkdirs()
        partial.writeBytes(payload)
        files += partial
        val result = GitHubUpdater.launchInstaller(context, partial)
        assertTrue(result.toString(), result is GitHubUpdater.InstallResult.Failure)
    }

    @Test
    fun `cancel releases a stalled network read without waiting for read timeout`() = runBlocking {
        val releaseServer = java.util.concurrent.CountDownLatch(1)
        val release = release(stall = releaseServer)
        val receiving = kotlinx.coroutines.CompletableDeferred<Unit>()
        val job = launch(kotlinx.coroutines.Dispatchers.IO) {
            GitHubUpdater.download(context, release) {
                if (it.downloadedBytes > 0L) receiving.complete(Unit)
            }
        }
        try {
            kotlinx.coroutines.withTimeout(5_000) { receiving.await() }
            kotlinx.coroutines.delay(100)
            kotlinx.coroutines.withTimeout(2_000) {
                job.cancel()
                job.join()
            }
            assertFalse(destination(release).exists())
            assertFalse(File(destination(release).path + ".part").exists())
        } finally {
            releaseServer.countDown()
            job.cancel()
            job.join()
        }
    }

    private fun destination(release: UpdateRelease) = File(context.cacheDir, "updates/${release.apk.name}")

    private fun release(
        contentLength: Int? = payload.size,
        stall: java.util.concurrent.CountDownLatch? = null
    ): UpdateRelease {
        val server = ServerSocket(0)
        servers += server
        thread(isDaemon = true) {
            server.accept().use { client ->
                val input = client.getInputStream().bufferedReader()
                while (!input.readLine().isNullOrEmpty()) Unit
                val header = buildString {
                    append("HTTP/1.1 200 OK\r\nConnection: close\r\n")
                    if (contentLength != null) append("Content-Length: $contentLength\r\n")
                    append("\r\n")
                }
                client.getOutputStream().apply {
                    write(header.toByteArray())
                    if (stall == null) {
                        write(payload)
                    } else {
                        write(payload, 0, 16_384)
                        flush()
                        stall.await(10, java.util.concurrent.TimeUnit.SECONDS)
                    }
                    flush()
                }
            }
        }
        val name = "test-${UUID.randomUUID()}.apk"
        val digest = MessageDigest.getInstance("SHA-256").digest(payload)
            .joinToString("") { "%02x".format(it.toInt() and 0xff) }
        val release = UpdateRelease(
            versionCode = 99, tag = "v99", title = "Test", notes = "", htmlUrl = "",
            apk = UpdateAsset(name, "http://127.0.0.1:${server.localPort}/update", 0, digest),
            checksum = null
        )
        files += destination(release)
        files += File(destination(release).path + ".part")
        return release
    }
}
