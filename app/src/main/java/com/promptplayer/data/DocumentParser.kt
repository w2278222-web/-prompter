package com.promptplayer.data

import android.content.Context
import android.net.Uri
import org.apache.poi.hwpf.HWPFDocument
import org.apache.poi.hwpf.extractor.WordExtractor
import org.apache.poi.xwpf.extractor.XWPFWordExtractor
import org.apache.poi.xwpf.usermodel.XWPFDocument
import java.nio.charset.Charset

class DocumentParser {

    data class ParseResult(
        val sentences: List<String>,
        val rawText: String
    )

    fun parseDocument(context: Context, uri: Uri, mimeType: String?): ParseResult {
        val fileName = resolveFileName(context, uri)
        val detectedType = detectDocType(mimeType, fileName, context, uri)

        val text = when (detectedType) {
            DocType.DOCX -> parseDocx(context, uri)
            DocType.DOC  -> parseDoc(context, uri)
            DocType.TXT  -> parseTxt(context, uri)
            else         -> parseTxt(context, uri)
        }

        return ParseResult(splitIntoSentences(text), text)
    }

    fun parseSentencesOnly(context: Context, uri: Uri, mimeType: String?): List<String> {
        return parseDocument(context, uri, mimeType).sentences
    }

    private enum class DocType { TXT, DOC, DOCX }

    private fun resolveFileName(context: Context, uri: Uri): String {
        try {
            val name = uri.lastPathSegment ?: return ""
            return name
        } catch (_: Exception) {
            return ""
        }
    }

    private fun detectDocType(mimeType: String?, fileName: String, context: Context, uri: Uri): DocType {
        // 1. MIME type
        val mime = mimeType ?: ""
        if (mime == "application/vnd.openxmlformats-officedocument.wordprocessingml.document") return DocType.DOCX
        if (mime == "application/msword") return DocType.DOC
        if (mime == "text/plain") return DocType.TXT

        // 2. File name extension (most reliable for .doc files on Android)
        val lower = fileName.lowercase()
        if (lower.endsWith(".docx")) return DocType.DOCX
        if (lower.endsWith(".doc")) return DocType.DOC
        if (lower.endsWith(".txt")) return DocType.TXT

        // 3. Magic bytes detection — OLE2 header (D0 CF 11 E0)
        try {
            context.contentResolver.openInputStream(uri)?.use { stream ->
                val header = ByteArray(4)
                if (stream.read(header) == 4) {
                    if (header[0] == 0xD0.toByte() && header[1] == 0xCF.toByte() &&
                        header[2] == 0x11.toByte() && header[3] == 0xE0.toByte()) {
                        return DocType.DOC
                    }
                    // ZIP header (PK) = likely DOCX
                    if (header[0] == 0x50.toByte() && header[1] == 0x4B.toByte()) {
                        if (lower.endsWith(".docx") || mime.contains("zip") || mime.contains("officedocument")) {
                            return DocType.DOCX
                        }
                    }
                }
            }
        } catch (_: Exception) { }

        return DocType.TXT
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

        if (bytes.size >= 3 && bytes[0] == 0xEF.toByte() && bytes[1] == 0xBB.toByte() && bytes[2] == 0xBF.toByte()) {
            return String(bytes, 3, bytes.size - 3, Charsets.UTF_8)
        }
        if (bytes.size >= 2 && bytes[0] == 0xFF.toByte() && bytes[1] == 0xFE.toByte()) {
            return String(bytes, 2, bytes.size - 2, Charset.forName("UTF-16LE"))
        }
        if (bytes.size >= 2 && bytes[0] == 0xFE.toByte() && bytes[1] == 0xFF.toByte()) {
            return String(bytes, 2, bytes.size - 2, Charset.forName("UTF-16BE"))
        }

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
        return try {
            context.contentResolver.openInputStream(uri)?.use { inputStream ->
                XWPFDocument(inputStream).use { document ->
                    XWPFWordExtractor(document).text
                }
            } ?: ""
        } catch (_: Exception) { "" }
    }

    private fun parseDoc(context: Context, uri: Uri): String {
        return try {
            context.contentResolver.openInputStream(uri)?.use { inputStream ->
                HWPFDocument(inputStream).use { document ->
                    WordExtractor(document).text
                }
            } ?: ""
        } catch (_: Exception) { "" }
    }

    fun splitIntoSentences(text: String): List<String> {
        val sentenceDelimiters = Regex("[。！？；，、：\n]+")
        return text.split(sentenceDelimiters)
            .map { it.trim() }
            .filter { it.isNotBlank() }
    }
}
