package com.clahbot.ocrlab

import android.graphics.Bitmap
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import java.io.Closeable
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.suspendCancellableCoroutine

/**
 * Bundled Latin ML Kit OCR. Kept behind this small interface so a Tesseract
 * implementation can be measured against it without changing the detector.
 */
interface OcrEngine : Closeable {
    suspend fun recognize(bitmap: Bitmap): OcrPage
}

class MlKitOcrEngine : OcrEngine {

    private val recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)

    override suspend fun recognize(bitmap: Bitmap): OcrPage =
        suspendCancellableCoroutine { continuation ->
            recognizer.process(InputImage.fromBitmap(bitmap, 0))
                .addOnSuccessListener { text ->
                    if (!continuation.isActive) return@addOnSuccessListener
                    val lines = text.textBlocks.flatMap { block ->
                        block.lines.map { line ->
                            OcrLine(
                                text = line.text,
                                box = line.boundingBox?.toOcrBox(),
                                elements = line.elements.map { element ->
                                    OcrElement(
                                        text = element.text,
                                        box = element.boundingBox?.toOcrBox()
                                    )
                                }
                            )
                        }
                    }
                    continuation.resume(
                        OcrPage(
                            width = bitmap.width,
                            height = bitmap.height,
                            lines = lines
                        )
                    )
                }
                .addOnFailureListener { error ->
                    if (continuation.isActive) continuation.resumeWithException(error)
                }
        }

    override fun close() {
        recognizer.close()
    }
}
