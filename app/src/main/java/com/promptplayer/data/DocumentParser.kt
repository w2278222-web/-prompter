package com.promptplayer.data

import android.content.Context
import android.net.Uri
import org.apache.poi.xwpf.extractor.XWPFWordExtractor
import org.apache.poi.xwpf.usermodel.XWPFDocument
import java.nio.charset.Charset

class DocumentParser {

    fun parseDocument(context: Context, uri: Uri, mimeType: String?): List<String> {
        return when {
            mimeType == "text/plain" || mimeType?.endsWith(".txt") == true -> parseTxt(context, uri)
            mimeType == "application/vnd.openxmlformats-officedocument.wordprocessingml.document" ||
            mimeType?.endsWith(".docx") == true -> parseDocx(context, uri)
            else -> tryParseAsTxt(context, uri)
        }
    }

    private fun parseTxt(context: Context, uri: Uri): List<String> {
        val sentences = mutableListOf<String>()
        context.contentResolver.openInputStream(uri)?.use { inputStream ->
            val bytes = inputStream.readBytes()
            // Try UTF-8 first. If replacement characters (U+FFFD) are found,
            // the file is likely GBK/GB18030 encoded (common on Chinese Windows).
            var text = String(bytes, Charsets.UTF_8)
            if (text.contains('�')) {
                text = String(bytes, Charset.forName("GB18030"))
            }
            sentences.addAll(splitIntoSentences(text))
        }
        return sentences
    }

    private fun parseDocx(context: Context, uri: Uri): List<String> {
        val sentences = mutableListOf<String>()
        context.contentResolver.openInputStream(uri)?.use { inputStream ->
            XWPFDocument(inputStream).use { document ->
                val extractor = XWPFWordExtractor(document)
                val fullText = extractor.text
                if (fullText.isNotBlank()) {
                    sentences.addAll(splitIntoSentences(fullText))
                }
            }
        }
        return sentences
    }

    private fun tryParseAsTxt(context: Context, uri: Uri): List<String> {
        return try {
            parseTxt(context, uri)
        } catch (e: Exception) {
            emptyList()
        }
    }

    private fun splitIntoSentences(text: String): List<String> {
        // Split on sentence-ending punctuation and pauses: 。！？；，、：\n
        val sentenceDelimiters = Regex("[。！？；，、：\n]+")
        return text.split(sentenceDelimiters)
            .map { it.trim() }
            .filter { it.isNotBlank() }
    }
}
