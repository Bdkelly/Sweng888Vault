
package com.example.sweng888vault.util

import android.content.Context
import android.graphics.BitmapFactory
import android.text.Html
import android.widget.Toast
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.text.PDFTextStripper
import nl.siegmann.epublib.epub.EpubReader
import org.apache.poi.hwpf.HWPFDocument
import org.apache.poi.hwpf.extractor.WordExtractor
import org.apache.poi.xwpf.usermodel.XWPFDocument
import java.io.File
import java.io.FileInputStream

/**
 * A helper object for handling all content processing logic.
 */

typealias OnTextExtractionResult = (text: String?, fileName: String) -> Unit
object Fileprocessing {

    // A typealias for the callback function for better readability
    /**
     * Recognizes text from an image file using ML Kit Text Recognition.
     */
    fun recognizeTextFromImage(file: File, context: Context, callback: OnTextExtractionResult) {
        val imageBitmap = BitmapFactory.decodeFile(file.absolutePath)
        val image = InputImage.fromBitmap(imageBitmap, 0)
        val recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)

        recognizer.process(image)
            .addOnSuccessListener { visionText ->
                val detectedText = visionText.text
                if (detectedText.isNotBlank()) {
                    callback(detectedText, file.name)
                } else {
                    Toast.makeText(context, "No text found in image", Toast.LENGTH_SHORT).show()
                    callback(null, file.name)
                }
            }
            .addOnFailureListener { e ->
                Toast.makeText(context, "Failed to read text: ${e.message}", Toast.LENGTH_SHORT).show()
                callback(null, file.name)
            }
    }

    /**
     * Extracts text from a .txt file.
     */
    fun readTextFromFile(file: File, context: Context, callback: OnTextExtractionResult) {
        try {
            val detectedText = file.readText(Charsets.UTF_8)
            if (detectedText.isNotBlank()) {
                callback(detectedText, file.name)
            } else {
                Toast.makeText(context, "Text file is empty", Toast.LENGTH_SHORT).show()
                callback(null, file.name)
            }
        } catch (e: Exception) {
            Toast.makeText(context, "Failed to read text file: ${e.message}", Toast.LENGTH_SHORT).show()
            callback(null, file.name)
        }
    }

    /**
     * Extracts text from a Word document (.doc or .docx).
     */
    fun readTextFromWord(file: File, context: Context, callback: OnTextExtractionResult) {
        try {
            val text = when (file.extension.lowercase()) {
                "docx" -> FileInputStream(file).use { XWPFDocument(it).paragraphs.joinToString("\n") { p -> p.text } }
                "doc" -> FileInputStream(file).use { WordExtractor(HWPFDocument(it)).text }
                else -> {
                    Toast.makeText(context, "Unsupported file format", Toast.LENGTH_SHORT).show()
                    return callback(null, file.name)
                }
            }

            if (text.isNotBlank()) {
                callback(text, file.name)
            } else {
                Toast.makeText(context, "No text found in Word document", Toast.LENGTH_LONG).show()
                callback(null, file.name)
            }
        } catch (e: Exception) {
            Toast.makeText(context, "Failed to read Word file: ${e.message}", Toast.LENGTH_SHORT).show()
            callback(null, file.name)
        }
    }

    /**
     * Extracts text from a PDF file.
     */
    fun readTextFromPdf(file: File, context: Context, callback: OnTextExtractionResult) {
        try {
            PDDocument.load(file).use { document ->
                val text = PDFTextStripper().getText(document).trim()
                if (text.isNotBlank()) {
                    callback(text, file.name)
                } else {
                    Toast.makeText(context, "No text found in PDF", Toast.LENGTH_LONG).show()
                    callback(null, file.name)
                }
            }
        } catch (e: Exception) {
            Toast.makeText(context, "Failed to read PDF file: ${e.message}", Toast.LENGTH_SHORT).show()
            callback(null, file.name)
        }
    }

    /**
     * Extracts text from an EPUB file.
     */
    fun readTextFromEpub(file: File, context: Context, callback: OnTextExtractionResult) {
        try {
            val book = EpubReader().readEpub(FileInputStream(file))
            val content = StringBuilder()

            for (resource in book.resources.all) {
                val href = resource.href.lowercase()
                if (href.endsWith(".html") || href.endsWith(".xhtml") || href.endsWith(".htm")) {
                    val html = resource.reader.readText()
                    val plainText = Html.fromHtml(html, Html.FROM_HTML_MODE_LEGACY).toString()
                    content.append(plainText).append("\n\n")
                }
            }
            val finalText = content.toString().trim()

            if (finalText.isNotBlank()) {
                callback(finalText, file.name)
            } else {
                Toast.makeText(context, "No readable text found in EPUB", Toast.LENGTH_LONG).show()
                callback(null, file.name)
            }
        } catch (e: Exception) {
            Toast.makeText(context, "Failed to read EPUB file: ${e.message}", Toast.LENGTH_SHORT).show()
            callback(null, file.name)
        }
    }
}