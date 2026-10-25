package com.clahbot.ocrlab

import android.graphics.BitmapFactory
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.widget.Button
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.lifecycle.lifecycleScope
import java.io.File
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Measurement-only lab. No attack data is written; the only output is a local
 * OCR dump for designing and scoring the results-screen detector.
 */
class MainActivity : ComponentActivity() {

    private val ocr: OcrEngine = MlKitOcrEngine()
    private lateinit var pathView: TextView
    private lateinit var statusView: TextView
    private lateinit var outputView: TextView
    private lateinit var dumpButton: Button
    private var busy = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        pathView = findViewById(R.id.tvPath)
        statusView = findViewById(R.id.tvStatus)
        outputView = findViewById(R.id.tvOutput)
        dumpButton = findViewById(R.id.btnDump)

        pathView.text = buildString {
            append("Test pack: ")
            append(testPackDir()?.absolutePath ?: "external files unavailable")
            append("\nApp: ")
            append(BuildConfig.APPLICATION_ID)
            append(" ")
            append(BuildConfig.VERSION_NAME)
            append(" (")
            append(BuildConfig.VERSION_CODE)
            append(") · Android ")
            append(Build.VERSION.RELEASE)
        }
        dumpButton.setOnClickListener { dumpTestPack() }
    }

    override fun onDestroy() {
        ocr.close()
        super.onDestroy()
    }

    private fun testPackDir(): File? =
        getExternalFilesDir(null)?.resolve("testpack")

    private fun dumpTestPack() {
        if (busy) return
        val directory = testPackDir()
        if (directory == null) {
            statusView.text = "External files directory unavailable"
            return
        }
        val images = directory.listFiles()
            ?.filter { it.isFile && it.extension.lowercase(Locale.US) in IMAGE_EXTENSIONS }
            ?.sortedBy { it.name }
            .orEmpty()
        if (images.isEmpty()) {
            statusView.text = "No images in ${directory.absolutePath}"
            outputView.text = "Push the 11 JPG screenshots into the folder shown above."
            return
        }

        busy = true
        dumpButton.isEnabled = false
        lifecycleScope.launch {
            val report = StringBuilder()
            report.appendLine("AUTOCLASH OCR LAB ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})")
            report.appendLine("testpack=${directory.absolutePath}")
            report.appendLine("images=${images.size}")
            var ok = 0
            var failed = 0
            for ((index, file) in images.withIndex()) {
                statusView.text = "OCR ${index + 1}/${images.size}: ${file.name}"
                report.appendLine()
                report.appendLine("===== ${file.name} =====")
                val bitmap = withContext(Dispatchers.IO) {
                    runCatching { BitmapFactory.decodeFile(file.absolutePath) }.getOrNull()
                }
                if (bitmap == null) {
                    failed++
                    report.appendLine("DECODE_FAILED")
                    continue
                }
                try {
                    val page = ocr.recognize(bitmap)
                    report.appendLine("size=${page.width}x${page.height} lines=${page.lines.size}")
                    for (line in page.lines) {
                        report.appendLine(
                            "LINE ${line.box?.format() ?: "-"} | ${line.text}"
                        )
                        for (element in line.elements) {
                            report.appendLine(
                                "  ELEM ${element.box?.format() ?: "-"} | ${element.text}"
                            )
                        }
                    }
                    ok++
                } catch (t: Throwable) {
                    failed++
                    report.appendLine("OCR_FAILED: ${t.javaClass.simpleName}: ${t.message}")
                    Log.w(TAG, "OCR failed for ${file.name}", t)
                } finally {
                    bitmap.recycle()
                }
            }
            val outputFile = File(filesDir, "ocr_dump.txt")
            withContext(Dispatchers.IO) {
                runCatching { outputFile.writeText(report.toString()) }
            }
            statusView.text = "OCR dump complete: $ok ok, $failed failed"
            outputView.text = report.toString().take(120_000)
            Log.i(TAG, "OCR dump $ok/$failed files=${images.size} file=${outputFile.absolutePath}")
            busy = false
            dumpButton.isEnabled = true
        }
    }

    companion object {
        private const val TAG = "OCR_LAB"
        private val IMAGE_EXTENSIONS = setOf("jpg", "jpeg", "png", "webp")
    }
}
