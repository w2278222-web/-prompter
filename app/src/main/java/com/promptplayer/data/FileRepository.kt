package com.promptplayer.data

import android.content.Context
import java.io.File

class FileRepository(private val context: Context) {

    private val promptsDir: File
        get() = File(context.filesDir, "prompts").also { it.mkdirs() }

    data class PromptFile(
        val name: String,
        val path: String,
        val lastModified: Long,
        val preview: String
    )

    fun saveText(name: String, content: String): File {
        val safeName = sanitizeFileName(name) + ".txt"
        val file = File(promptsDir, safeName)
        file.writeText(content, Charsets.UTF_8)
        return file
    }

    fun saveTextWithTimestamp(content: String): File {
        val name = "文稿_${System.currentTimeMillis()}"
        return saveText(name, content)
    }

    fun listFiles(): List<PromptFile> {
        val files = promptsDir.listFiles() ?: return emptyList()
        return files
            .filter { it.isFile && it.extension.equals("txt", ignoreCase = true) }
            .sortedByDescending { it.lastModified() }
            .map { file ->
                val preview = try {
                    val text = file.readText(Charsets.UTF_8)
                    if (text.length > 50) text.take(50) + "..." else text
                } catch (_: Exception) {
                    "(无法预览)"
                }
                PromptFile(
                    name = file.nameWithoutExtension,
                    path = file.absolutePath,
                    lastModified = file.lastModified(),
                    preview = preview
                )
            }
    }

    fun loadContent(path: String): String? {
        return try {
            File(path).readText(Charsets.UTF_8)
        } catch (_: Exception) {
            null
        }
    }

    fun deleteFile(path: String): Boolean {
        return File(path).delete()
    }

    fun saveEncodedText(rawContent: String, sourceName: String): File {
        val baseName = sourceName
            .substringAfterLast("/")
            .substringAfterLast("\\")
            .substringBeforeLast(".")
            .ifEmpty { "导入文稿" }
        return saveText(baseName + "_" + System.currentTimeMillis(), rawContent)
    }

    private fun sanitizeFileName(name: String): String {
        return name.replace(Regex("""[\\/:*?"<>|]"""), "_")
            .trim()
            .ifEmpty { "未命名" }
    }
}
