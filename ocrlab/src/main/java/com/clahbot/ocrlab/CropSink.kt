package com.clahbot.ocrlab

import android.graphics.Bitmap
import java.io.File
import java.io.FileOutputStream

/** Saves the exact bitmap passed to each reader for later inspection. */
class CropSink(private val directory: File) {

    fun save(tag: String, bitmap: Bitmap): String? {
        return try {
            directory.mkdirs()
            val file = File(directory, "$tag.png")
            FileOutputStream(file).use { output ->
                bitmap.compress(Bitmap.CompressFormat.PNG, 100, output)
            }
            file.absolutePath
        } catch (_: Throwable) {
            null
        }
    }
}
