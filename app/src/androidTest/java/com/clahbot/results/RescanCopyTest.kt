package com.clahbot.results

import android.content.Context
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.clahbot.data.AttackRepository
import java.io.File
import java.security.MessageDigest
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Runs the dashboard re-scan against a copy of the live history. The real
 * attacks.json is only hashed, never opened for writing.
 */
@RunWith(AndroidJUnit4::class)
class RescanCopyTest {

    @Test
    fun rescanUpdatesOnlyTheCopy() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val fixture = File(context.getExternalFilesDir(null), "rescan_fixture/attacks.json")
        require(fixture.isFile) { "missing fixture at ${fixture.absolutePath}" }
        val workDir = File(context.filesDir, "rescan_test").apply {
            deleteRecursively()
            mkdirs()
        }
        val copy = File(workDir, "attacks.json")
        fixture.copyTo(copy)
        val original = File(context.filesDir, "attacks.json")
        val originalHash = hash(original)

        val repository = AttackRepository(copy)
        val before = repository.refresh().associateBy { it.id }
        val summary = ResultsRescan.run(context, repository)
        val after = repository.refresh().associateBy { it.id }

        val changedIds = after.values.filter { record ->
            val old = before[record.id] ?: return@filter false
            old != record
        }.map { it.id }.toSet()

        val report = StringBuilder()
        report.appendLine("fixtureRecords=${before.size}")
        report.appendLine("scanned=${summary.scanned} updated=${summary.updated} changed=${changedIds.size}")
        for (id in changedIds) {
            val old = before.getValue(id)
            val new = after.getValue(id)
            report.appendLine(
                "id=$id before=${old.gold}/${old.elixir}/${old.darkElixir} " +
                    "src=${old.captureSource} after=${new.gold}/${new.elixir}/${new.darkElixir} " +
                    "src=${new.captureSource} bonus=${new.bonusGold}/${new.bonusElixir}/${new.bonusDarkElixir}"
            )
        }
        val candidates = before.values.filter {
            it.confirmed && it.resultsScreenshot != null && (!it.hasLoot || !it.complete)
        }
        report.appendLine("candidates=${candidates.size} ids=${candidates.map { it.id }}")
        report.appendLine("originalHashBefore=$originalHash")
        report.appendLine("originalHashAfter=${hash(original)}")
        File(context.getExternalFilesDir(null), "rescan_test_report.txt").writeText(report.toString())

        assertEquals("re-scan must not touch the live history", originalHash, hash(original))
        assertEquals("every applied update must be counted", changedIds.size, summary.updated)
        assertTrue(
            "re-scan reported changes without a changed record",
            summary.updated == 0 || changedIds.isNotEmpty()
        )
        for (id in changedIds) {
            val record = after.getValue(id)
            assertNotNull("gold null after update id=$id", record.gold)
            assertNotNull("elixir null after update id=$id", record.elixir)
            assertNotNull("dark null after update id=$id", record.darkElixir)
            assertEquals("re-scan source id=$id", "re-scanned", record.captureSource)
            assertNotNull("screenshot dropped id=$id", record.resultsScreenshot)
        }
        for ((id, old) in before) {
            if (id !in changedIds) assertEquals("record $id changed without an update", old, after[id])
        }
    }

    private fun hash(file: File): String {
        if (!file.isFile) return "missing"
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(8192)
            while (true) {
                val read = input.read(buffer)
                if (read <= 0) break
                digest.update(buffer, 0, read)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }
}
