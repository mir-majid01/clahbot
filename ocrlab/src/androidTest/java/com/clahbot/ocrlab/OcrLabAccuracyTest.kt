package com.clahbot.ocrlab

import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.google.gson.GsonBuilder
import java.io.File
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Headless rerun of the accuracy pack. This does not launch the lab Activity
 * and does not interact with the screen.
 */
@RunWith(AndroidJUnit4::class)
class OcrLabAccuracyTest {

    @Test
    fun runPack() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val directory = requireNotNull(context.getExternalFilesDir(null)?.resolve("testpack")) {
            "external testpack directory unavailable"
        }
        require(directory.isDirectory) { "testpack folder missing at ${directory.absolutePath}" }
        val images = directory.listFiles()
            ?.filter { it.isFile && it.extension.lowercase() in setOf("jpg", "jpeg", "png", "webp") }
            .orEmpty()
        assertTrue("expected the 11-image pack, found ${images.size}", images.size >= 11)

        val ocr = MlKitOcrEngine()
        try {
            val report = AccuracyRunner(context).run(directory, ocr)
            val json = GsonBuilder().setPrettyPrinting().create().toJson(report)
            val internal = File(context.filesDir, "accuracy_report.json")
            internal.writeText(json)
            val runReport = File(report.runDirectory, "accuracy_report.json")
            runReport.parentFile?.mkdirs()
            runReport.writeText(json)
            Log.i(
                "OCR_LAB",
                "Headless accuracy report ${runReport.absolutePath} images=${report.images} " +
                    "detected=${report.detectedResults} wrong=${report.wrongCaptures}"
            )
            assertTrue("no screenshots processed", report.images >= 11)
            assertEquals(
                "result screens missed: ${report.missedResults}",
                report.expectedResults,
                report.detectedResults
            )
            assertEquals("false-positive results screens", 0, report.falsePositives)
            assertTrue(
                "wrong captures=${report.wrongCaptures}: " +
                    report.wrongCaptureList.joinToString("; "),
                report.wrongCaptures == 0
            )
        } finally {
            ocr.close()
        }
    }
}
