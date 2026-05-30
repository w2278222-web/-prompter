package com.promptplayer.presentation

import android.content.Context
import android.net.Uri
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import com.promptplayer.data.DocumentParser
import com.promptplayer.data.FileRepository
import com.promptplayer.domain.TextMatcher
import com.promptplayer.speech.SpeechRecognitionHelper
import kotlinx.coroutines.flow.StateFlow
import java.io.File

class PromptViewModel : ViewModel() {

    private val documentParser = DocumentParser()
    private var speechHelper: SpeechRecognitionHelper? = null
    private var fileRepository: FileRepository? = null

    var sentences by mutableStateOf<List<String>>(emptyList())
        private set

    var currentIndex by mutableIntStateOf(0)
        private set

    var isListening by mutableStateOf(false)
        private set

    var baseFontSize by mutableIntStateOf(24)
        private set

    var recognizedText by mutableStateOf("")
        private set

    var savedFiles by mutableStateOf<List<FileRepository.PromptFile>>(emptyList())
        private set

    var currentFileName by mutableStateOf<String?>(null)
        private set

    private var lastMatchedIndex = 0

    private fun getRepo(context: Context): FileRepository {
        if (fileRepository == null) {
            fileRepository = FileRepository(context)
        }
        return fileRepository!!
    }

    fun loadDocument(context: Context, uri: Uri, mimeType: String?) {
        val result = documentParser.parseDocument(context, uri, mimeType)

        // Encoding normalization: save a UTF-8 copy to app storage
        try {
            val sourceName = try {
                uri.lastPathSegment ?: "导入文稿"
            } catch (_: Exception) { "导入文稿" }
            getRepo(context).saveEncodedText(result.rawText, sourceName)
        } catch (_: Exception) { }

        sentences = result.sentences
        currentIndex = 0
        lastMatchedIndex = 0
        currentFileName = null
    }

    fun loadSavedFile(context: Context, file: FileRepository.PromptFile) {
        val content = getRepo(context).loadContent(file.path)
        if (content != null) {
            sentences = documentParser.splitIntoSentences(content)
            currentIndex = 0
            lastMatchedIndex = 0
            currentFileName = file.name
        }
    }

    fun saveUserText(context: Context, text: String, name: String?) {
        if (text.isBlank()) return
        val repo = getRepo(context)
        val file = if (name.isNullOrBlank()) {
            repo.saveTextWithTimestamp(text)
        } else {
            repo.saveText(name, text)
        }
        sentences = documentParser.splitIntoSentences(text)
        currentIndex = 0
        lastMatchedIndex = 0
        currentFileName = file.nameWithoutExtension
        refreshFileList(context)
    }

    fun deleteFile(context: Context, file: FileRepository.PromptFile) {
        getRepo(context).deleteFile(file.path)
        refreshFileList(context)
    }

    fun refreshFileList(context: Context) {
        savedFiles = getRepo(context).listFiles()
    }

    fun initSpeechRecognition(context: Context) {
        if (speechHelper == null) {
            speechHelper = SpeechRecognitionHelper(context)
        }
    }

    fun startListening() {
        isListening = true
        speechHelper?.startListening { recognized ->
            recognizedText = recognized
            if (sentences.isNotEmpty()) {
                val newIndex = TextMatcher.findBestMatchIndex(recognized, sentences, lastMatchedIndex)
                if (newIndex != lastMatchedIndex) {
                    currentIndex = newIndex
                    lastMatchedIndex = newIndex
                }
            }
        }
    }

    fun stopListening() {
        isListening = false
        speechHelper?.stopListening()
    }

    fun setFontSize(size: Int) {
        baseFontSize = size
    }

    fun resetPosition() {
        currentIndex = 0
        lastMatchedIndex = 0
    }

    override fun onCleared() {
        super.onCleared()
        speechHelper?.destroy()
    }
}
