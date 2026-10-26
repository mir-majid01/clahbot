package com.clahbot.ocrlab

import com.clahbot.ocr.*

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
        val allImages = directory.listFiles()
            ?.filter { it.isFile && it.extension.lowercase() in setOf("jpg", "jpeg", "png", "webp") }
            .orEmpty()
        val args = InstrumentationRegistry.getArguments()
        val subset = args.getString("subset") == "results"
        val imageNames = if (subset) RESULT_IMAGES else null
        val saveCrops = !subset
        val available = if (imageNames == null) {
            allImages
        } else {
            allImages.filter { it.name in imageNames }
        }
        if (subset) {
            assertEquals("expected the four result screens, found ${available.size}", 4, available.size)
        } else {
            assertTrue("expected the 11-image pack, found ${available.size}", available.size >= 11)
        }

        val strategy = when (args.getString("strategy")) {
            "all" -> ReaderStrategy.ALL
            else -> ReaderStrategy.CHEAP_FIRST
        }
        val ocr = MlKitOcrEngine()
        try {
            val report = AccuracyRunner(context).run(
                directory = directory,
                ocr = ocr,
                strategy = strategy,
                imageNames = imageNames,
                saveCrops = saveCrops
            )
            val json = GsonBuilder().setPrettyPrinting().create().toJson(report)
            val suffix = buildString {
                if (strategy == ReaderStrategy.ALL) append("_all")
                if (subset) append("_results")
            }
            val internal = File(context.filesDir, "accuracy_report$suffix.json")
            internal.writeText(json)
            val runReport = File(report.runDirectory, "accuracy_report.json")
            runReport.parentFile?.mkdirs()
            runReport.writeText(json)
            Log.i(
                "OCR_LAB",
                "Headless accuracy report ${runReport.absolutePath} strategy=${report.strategy} " +
                    "subset=$subset images=${report.images} detected=${report.detectedResults} " +
                    "wrong=${report.wrongCaptures} missing=${report.notCaptured}"
            )
            if (subset) {
                assertEquals("result screens processed", 4, report.images)
            } else {
                assertTrue("no screenshots processed", report.images >= 11)
            }
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

    companion object {
        private val RESULT_IMAGES = setOf(
            "08_results_victory_2star_70.jpg",
            "09_results_defeat_35.jpg",
            "10_results_victory_3star_100.jpg",
            "11_results_victory_2star_71_reference.jpg"
        )
    }
}
