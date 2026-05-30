package com.promptplayer.data

import android.content.Context
import android.net.Uri
import org.apache.poi.hwpf.HWPFDocument
import org.apache.poi.xwpf.extractor.XWPFWordExtractor
import org.apache.poi.xwpf.usermodel.XWPFDocument
import java.nio.charset.Charset

class DocumentParser {

    data class ParseResult(
        val sentences: List<String>,
        val rawText: String
    )

    fun parseDocument(context: Context, uri: Uri, mimeType: String?): ParseResult {
        return when {
            mimeType == "text/plain" || mimeType?.endsWith(".txt") == true -> {
                val text = parseTxt(context, uri)
                ParseResult(splitIntoSentences(text), text)
            }
            mimeType == "application/vnd.openxmlformats-officedocument.wordprocessingml.document" ||
            mimeType?.endsWith(".docx") == true -> {
                val text = parseDocx(context, uri)
                ParseResult(splitIntoSentences(text), text)
            }
            mimeType == "application/msword" || mimeType?.endsWith(".doc") == true -> {
                val text = parseDoc(context, uri)
                ParseResult(splitIntoSentences(text), text)
            }
            else -> {
                val text = tryParseAsTxt(context, uri)
                ParseResult(splitIntoSentences(text), text)
            }
        }
    }

    fun parseSentencesOnly(context: Context, uri: Uri, mimeType: String?): List<String> {
        return parseDocument(context, uri, mimeType).sentences
    }

    private fun parseTxt(context: Context, uri: Uri): String {
        context.contentResolver.openInputStream(uri)?.use { inputStream ->
            val bytes = inputStream.readBytes()
            return detectAndDecode(bytes)
        }
        return ""
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
                ch in '\u4e00'..'\u9fff' -> score += 3
                ch in '\u3400'..'\u4dbf' -> score += 3
                ch in '\uf900'..'\ufaff' -> score += 3
                ch in '\u3000'..'\u303f' -> score += 1
                ch in '\uff00'..'\uffef' -> score += 1
                ch == '\ufffd'          -> score -= 5
            }
        }
        return score
    }

    private fun parseDocx(context: Context, uri: Uri): String {
        context.contentResolver.openInputStream(uri)?.use { inputStream ->
            XWPFDocument(inputStream).use { document ->
                val extractor = XWPFWordExtractor(document)
                return extractor.text
            }
        }
        return ""
    }

    private fun parseDoc(context: Context, uri: Uri): String {
        context.contentResolver.openInputStream(uri)?.use { inputStream ->
            HWPFDocument(inputStream).use { document ->
                return document.documentText
            }
        }
        return ""
    }

    private fun tryParseAsTxt(context: Context, uri: Uri): String {
        return try {
            parseTxt(context, uri)
        } catch (e: Exception) {
            ""
        }
    }

    fun splitIntoSentences(text: String): List<String> {
        val sentenceDelimiters = Regex("[。！？；，、：\n]+")
        return text.split(sentenceDelimiters)
            .map { it.trim() }
            .filter { it.isNotBlank() }
    }
}
