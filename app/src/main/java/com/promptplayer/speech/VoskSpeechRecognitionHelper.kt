package com.promptplayer.speech

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import androidx.core.content.ContextCompat
import kotlinx.coroutines.*
import org.vosk.Model
import org.vosk.Recognizer
import java.io.File
import java.io.FileOutputStream
import java.util.zip.ZipInputStream

class VoskSpeechRecognitionHelper(private val context: Context) {

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var recognitionJob: Job? = null
    private var audioRecord: AudioRecord? = null
    private var voskModel: Model? = null
    private var voskRecognizer: Recognizer? = null

    private var onResultCallback: ((String) -> Unit)? = null
    private var onPartialResultCallback: ((String) -> Unit)? = null
    private var onErrorCallback: ((String) -> Unit)? = null
    private var onModelReadyCallback: (() -> Unit)? = null

    private var isRecording = false
    @Volatile var isModelReady = false
        private set

    companion object {
        private const val SAMPLE_RATE = 16000
        private const val CHANNEL_CONFIG = AudioFormat.CHANNEL_IN_MONO
        private const val AUDIO_FORMAT = AudioFormat.ENCODING_PCM_16BIT
        private const val MODEL_DIR = "vosk-model"
    }

    private fun getBufferSize(): Int {
        val minBuffer = AudioRecord.getMinBufferSize(SAMPLE_RATE, CHANNEL_CONFIG, AUDIO_FORMAT)
        return maxOf(minBuffer, SAMPLE_RATE * 2)
    }

    fun initModel(onReady: () -> Unit, onError: (String) -> Unit) {
        this.onModelReadyCallback = onReady
        this.onErrorCallback = onError

        scope.launch {
            try {
                val modelPath = extractModelIfNeeded()
                voskModel = Model(modelPath)
                isModelReady = true
                withContext(Dispatchers.Main) {
                    onModelReadyCallback?.invoke()
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    onErrorCallback?.invoke("模型加载失败: ${e.message}")
                }
            }
        }
    }

    private fun extractModelIfNeeded(): String {
        val targetDir = File(context.filesDir, MODEL_DIR)
        val marker = File(targetDir, ".extracted")

        if (marker.exists()) {
            val subdir = targetDir.listFiles()?.firstOrNull { it.isDirectory }
            if (subdir != null) return subdir.absolutePath
            // Corrupted extraction, re-extract
            targetDir.deleteRecursively()
            targetDir.mkdirs()
        }

        targetDir.deleteRecursively()
        targetDir.mkdirs()

        var modelSubDir = ""
        val buffer = ByteArray(8192)
        context.assets.open("vosk-model-cn/model.zip").use { input ->
            ZipInputStream(input).use { zip ->
                var entry = zip.nextEntry
                while (entry != null) {
                    val outFile = File(targetDir, entry.name)
                    if (entry.isDirectory) {
                        outFile.mkdirs()
                        if (modelSubDir.isEmpty() && entry.name.contains("/") && !entry.name.startsWith("__")) {
                            modelSubDir = entry.name.trimEnd('/')
                        }
                    } else {
                        outFile.parentFile?.mkdirs()
                        FileOutputStream(outFile).use { output ->
                            var len: Int
                            while (zip.read(buffer).also { len = it } > 0) {
                                output.write(buffer, 0, len)
                            }
                        }
                    }
                    zip.closeEntry()
                    entry = zip.nextEntry
                }
            }
        }

        marker.createNewFile()
        return File(targetDir, modelSubDir).absolutePath
    }

    fun startListening(
        onPartialResult: (String) -> Unit,
        onResult: (String) -> Unit,
        onError: (String) -> Unit
    ) {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO)
            != PackageManager.PERMISSION_GRANTED
        ) {
            onError("没有麦克风权限")
            return
        }

        if (!isModelReady || voskModel == null) {
            onError("模型未就绪")
            return
        }

        this.onPartialResultCallback = onPartialResult
        this.onResultCallback = onResult
        this.onErrorCallback = onError

        try {
            val bufferSize = getBufferSize()

            audioRecord = AudioRecord(
                MediaRecorder.AudioSource.MIC,
                SAMPLE_RATE,
                CHANNEL_CONFIG,
                AUDIO_FORMAT,
                bufferSize
            )

            if (audioRecord?.state != AudioRecord.STATE_INITIALIZED) {
                onError("音频初始化失败")
                return
            }

            isRecording = true
            audioRecord?.startRecording()

            recognitionJob = scope.launch {
                val model = voskModel!!
                voskRecognizer = Recognizer(model, SAMPLE_RATE.toFloat())
                val buffer = ByteArray(3200) // ~100ms @ 16kHz mono 16-bit

                while (isActive && isRecording) {
                    val bytesRead = audioRecord?.read(buffer, 0, buffer.size) ?: -1
                    if (bytesRead <= 0) continue

                    val accepted = voskRecognizer?.acceptWaveForm(buffer, bytesRead) ?: false

                    // Check partial result every time for real-time matching
                    val partial = voskRecognizer?.partialResult
                    if (!partial.isNullOrEmpty() && partial != "{}") {
                        val text = extractText(partial, "partial")
                        if (text.isNotBlank()) {
                            withContext(Dispatchers.Main) {
                                onPartialResultCallback?.invoke(text)
                            }
                        }
                    }

                    if (accepted) {
                        val resultJson = voskRecognizer?.result
                        if (!resultJson.isNullOrEmpty()) {
                            val text = extractText(resultJson, "text")
                            if (text.isNotBlank()) {
                                withContext(Dispatchers.Main) {
                                    onResultCallback?.invoke(text)
                                }
                            }
                        }
                        // Create new recognizer for next utterance
                        voskRecognizer?.close()
                        voskRecognizer = Recognizer(model, SAMPLE_RATE.toFloat())
                    }
                }
            }
        } catch (e: Exception) {
            onError("启动失败: ${e.message}")
        }
    }

    private fun extractText(json: String, key: String): String {
        return try {
            val start = json.indexOf("\"$key\"")
            if (start < 0) return ""
            val colon = json.indexOf(':', start)
            if (colon < 0) return ""
            val valueStart = json.indexOf('"', colon)
            if (valueStart < 0) return ""
            val valueEnd = json.indexOf('"', valueStart + 1)
            if (valueEnd < 0) return ""
            json.substring(valueStart + 1, valueEnd).trim()
        } catch (e: Exception) {
            ""
        }
    }

    fun stopListening() {
        isRecording = false
        recognitionJob?.cancel()

        try {
            audioRecord?.stop()
            audioRecord?.release()
        } catch (_: Exception) {}
        audioRecord = null

        try { voskRecognizer?.close() } catch (_: Exception) {}
        voskRecognizer = null
    }

    fun destroy() {
        stopListening()
        scope.cancel()
        try { voskModel?.close() } catch (_: Exception) {}
        voskModel = null
    }
}
