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
            val text = detectAndDecode(bytes)
            sentences.addAll(splitIntoSentences(text))
        }
        return sentences
    }

    private fun detectAndDecode(bytes: ByteArray): String {
        if (bytes.isEmpty()) return ""

        // 1. BOM detection
        if (bytes.size >= 3 && bytes[0] == 0xEF.toByte() && bytes[1] == 0xBB.toByte() && bytes[2] == 0xBF.toByte()) {
            return String(bytes, 3, bytes.size - 3, Charsets.UTF_8)
        }
        if (bytes.size >= 2 && bytes[0] == 0xFF.toByte() && bytes[1] == 0xFE.toByte()) {
            return String(bytes, 2, bytes.size - 2, Charset.forName("UTF-16LE"))
        }
        if (bytes.size >= 2 && bytes[0] == 0xFE.toByte() && bytes[1] == 0xFF.toByte()) {
            return String(bytes, 2, bytes.size - 2, Charset.forName("UTF-16BE"))
        }

        // 2. Multi-encoding scoring: decode with each candidate, score by CJK character ratio
        data class Candidate(val encoding: String, val text: String, val score: Int)

        val best = listOf("UTF-8", "GBK", "GB18030")
            .mapNotNull { encoding ->
                try {
                    val text = String(bytes, Charset.forName(encoding))
                    Candidate(encoding, text, scoreChineseText(text))
                } catch (_: Exception) { null }
            }
            .maxByOrNull { it.score }

        return best?.text ?: String(bytes, Charsets.UTF_8)
    }

    private fun scoreChineseText(text: String): Int {
        var score = 0
        for (ch in text) {
            when {
                ch in '\u4e00'..'\u9fff' -> score += 3       // CJK unified ideographs
                ch in '\u3400'..'\u4dbf' -> score += 3       // CJK extension A
                ch in '\uf900'..'\ufaff' -> score += 3       // CJK compatibility
                ch in '\u3000'..'\u303f' -> score += 1       // CJK punctuation
                ch in '\uff00'..'\uffef' -> score += 1       // fullwidth forms
                ch == '\ufffd'          -> score -= 5        // replacement char penalty
            }
        }
        return score
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
