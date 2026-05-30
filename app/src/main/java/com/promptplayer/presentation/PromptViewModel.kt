package com.promptplayer.presentation

import android.content.Context
import android.net.Uri
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import com.promptplayer.data.DocumentParser
import com.promptplayer.domain.TextMatcher
import com.promptplayer.speech.SpeechRecognitionHelper
import kotlinx.coroutines.flow.StateFlow

class PromptViewModel : ViewModel() {

    private val documentParser = DocumentParser()
    private var speechHelper: SpeechRecognitionHelper? = null

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

    private var lastMatchedIndex = 0

    fun loadDocument(context: Context, uri: Uri, mimeType: String?) {
        sentences = documentParser.parseDocument(context, uri, mimeType)
        currentIndex = 0
        lastMatchedIndex = 0
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
