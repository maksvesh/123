package ru.screentranslator

import android.graphics.Bitmap
import android.graphics.Rect
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.TextRecognizer
import com.google.mlkit.vision.text.TextRecognizerOptionsInterface
import com.google.mlkit.vision.text.chinese.ChineseTextRecognizerOptions
import com.google.mlkit.vision.text.devanagari.DevanagariTextRecognizerOptions
import com.google.mlkit.vision.text.japanese.JapaneseTextRecognizerOptions
import com.google.mlkit.vision.text.korean.KoreanTextRecognizerOptions
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import kotlinx.coroutines.tasks.await

data class OcrBlock(val text: String, val box: Rect)

/** On-device text finder (ML Kit). Only locates text; translation always goes through the API. */
class OcrEngine(script: OcrScript) {

    private val recognizer: TextRecognizer = TextRecognition.getClient(optionsFor(script))

    suspend fun recognize(bitmap: Bitmap): List<OcrBlock> {
        val result = recognizer.process(InputImage.fromBitmap(bitmap, 0)).await()
        return result.textBlocks
            .flatMap { it.lines }
            .mapNotNull { line -> line.boundingBox?.let { OcrBlock(line.text, it) } }
    }

    fun close() = recognizer.close()

    private fun optionsFor(script: OcrScript): TextRecognizerOptionsInterface = when (script) {
        OcrScript.LATIN -> TextRecognizerOptions.DEFAULT_OPTIONS
        OcrScript.CHINESE -> ChineseTextRecognizerOptions.Builder().build()
        OcrScript.JAPANESE -> JapaneseTextRecognizerOptions.Builder().build()
        OcrScript.KOREAN -> KoreanTextRecognizerOptions.Builder().build()
        OcrScript.DEVANAGARI -> DevanagariTextRecognizerOptions.Builder().build()
    }
}
