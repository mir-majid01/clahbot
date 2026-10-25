package com.clahbot.ocrlab

import android.content.Context
import android.graphics.Bitmap
import com.googlecode.tesseract.android.TessBaseAPI
import java.io.Closeable
import java.io.File

/**
 * On-device Tesseract used only on small row crops. The bundled model is
 * copied from assets into app-private storage on first use.
 */
class TesseractRowEngine private constructor(
    private val tess: TessBaseAPI
) : Closeable {

    fun recognize(bitmap: Bitmap): String {
        tess.setImage(bitmap)
        val text = tess.getUTF8Text() ?: ""
        tess.clear()
        return text.trim()
    }

    override fun close() {
        tess.recycle()
    }

    companion object {
        fun create(context: Context): TesseractRowEngine {
            val dataRoot = File(context.filesDir, "tesseract")
            val model = File(dataRoot, "tessdata/eng.traineddata")
            if (!model.isFile) {
                model.parentFile?.mkdirs()
                context.assets.open("tessdata/eng.traineddata").use { input ->
                    model.outputStream().use { output -> input.copyTo(output) }
                }
            }
            val tess = TessBaseAPI()
            if (!tess.init(dataRoot.absolutePath, "eng")) {
                tess.recycle()
                throw IllegalStateException("Tesseract could not initialize eng model")
            }
            tess.setPageSegMode(TessBaseAPI.PageSegMode.PSM_SINGLE_LINE)
            tess.setVariable("tessedit_char_whitelist", "0123456789 +")
            return TesseractRowEngine(tess)
        }
    }
}
