package com.promptplayer.presentation

import android.Manifest
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.lifecycle.viewmodel.compose.viewModel
import com.promptplayer.domain.TextMatcher
import com.promptplayer.speech.VoskSpeechRecognitionHelper

// ── Color palette ──────────────────────────────────────────────────
private val GradientStart   = Color(0xFF0D0D1A)
private val GradientEnd     = Color(0xFF1A1A2E)
private val AccentBlue      = Color(0xFF64B5F6)
private val AccentTeal      = Color(0xFF4DD0E1)
private val SurfaceDark     = Color(0xFF1E1E30)
private val RedAccent       = Color(0xFFEF5350)
private val GreenStatus     = Color(0xFF66BB6A)
private val AmberStatus     = Color(0xFFFFA726)
private val GreyStatus      = Color(0xFF757575)

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { PromptPlayerApp() }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PromptPlayerApp(viewModel: PromptViewModel = viewModel()) {
    val context = LocalContext.current
    val listState = rememberLazyListState()

    var sentences by remember { mutableStateOf<List<String>>(emptyList()) }
    var currentIndex by remember { mutableIntStateOf(0) }
    var isListening by remember { mutableStateOf(false) }
    var baseFontSize by remember { mutableIntStateOf(22) }
    var recognizedText by remember { mutableStateOf("") }
    var showSettings by remember { mutableStateOf(false) }
    var speechError by remember { mutableStateOf<String?>(null) }

    // Vosk state
    var voskHelper by remember { mutableStateOf<VoskSpeechRecognitionHelper?>(null) }
    var modelReady by remember { mutableStateOf(false) }
    var modelLoading by remember { mutableStateOf(false) }
    var lastMatchedIndex by remember { mutableIntStateOf(0) }

    // Smooth animated position for sentence transitions
    val animatedIndex by animateFloatAsState(
        targetValue = currentIndex.toFloat(),
        animationSpec = tween(durationMillis = 350)
    )

    // Scroll target for smooth, multi-trigger scrolling.
    // Updated by both currentIndex changes and end-of-sentence detection.
    var scrollTarget by remember { mutableIntStateOf(0) }

    val documentPickerLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri: Uri? ->
        uri?.let {
            val mimeType = context.contentResolver.getType(it)
            viewModel.loadDocument(context, it, mimeType)
            sentences = viewModel.sentences
            currentIndex = 0
            lastMatchedIndex = 0
        }
    }

    fun startVoskListening() {
        val helper = voskHelper
        if (helper == null || !modelReady) return
        isListening = true
        speechError = null
        recognizedText = ""
        helper.startListening(
            onPartialResult = { partial ->
                recognizedText = partial
                if (sentences.isNotEmpty() && partial.isNotBlank()) {
                    val newIndex = TextMatcher.findBestMatchIndex(partial, sentences, lastMatchedIndex)
                    if (newIndex != lastMatchedIndex) {
                        currentIndex = newIndex
                        lastMatchedIndex = newIndex
                    } else if (lastMatchedIndex < sentences.size - 1) {
                        // Index unchanged — check if user is at the end of the
                        // current sentence and pre-scroll to show the next one.
                        if (TextMatcher.isEndOfSentence(partial, sentences[lastMatchedIndex])) {
                            scrollTarget = maxOf(0, lastMatchedIndex - 1)
                        }
                    }
                }
            },
            onResult = { final ->
                recognizedText = final
                speechError = null
                if (sentences.isNotEmpty() && final.isNotBlank()) {
                    val newIndex = TextMatcher.findBestMatchIndex(final, sentences, lastMatchedIndex)
                    if (newIndex != lastMatchedIndex) {
                        currentIndex = newIndex
                        lastMatchedIndex = newIndex
                    } else if (lastMatchedIndex < sentences.size - 1) {
                        if (TextMatcher.isEndOfSentence(final, sentences[lastMatchedIndex])) {
                            scrollTarget = maxOf(0, lastMatchedIndex - 1)
                        }
                    }
                }
            },
            onError = { error ->
                speechError = error
                isListening = false
            }
        )
    }

    val micPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) {
            startVoskListening()
        } else {
            Toast.makeText(context, "需要麦克风权限", Toast.LENGTH_SHORT).show()
        }
    }

    // Scroll when scrollTarget changes (triggered by both currentIndex
    // changes and end-of-sentence pre-scroll detection).
    LaunchedEffect(scrollTarget) {
        if (sentences.isNotEmpty() && scrollTarget >= 0) {
            listState.animateScrollToItem(scrollTarget)
        }
    }

    // Update scrollTarget when currentIndex changes (normal sentence advance).
    LaunchedEffect(currentIndex) {
        if (sentences.isNotEmpty() && currentIndex >= 0) {
            scrollTarget = maxOf(0, currentIndex - 2)
        }
    }

    LaunchedEffect(sentences) {
        if (sentences.isNotEmpty()) {
            listState.animateScrollToItem(0)
        }
    }

    // Initialize Vosk model
    LaunchedEffect(Unit) {
        if (voskHelper == null) {
            modelLoading = true
            voskHelper = VoskSpeechRecognitionHelper(context)
            voskHelper?.initModel(
                onReady = {
                    modelReady = true
                    modelLoading = false
                },
                onError = { error ->
                    speechError = error
                    modelLoading = false
                }
            )
        }
    }

    DisposableEffect(Unit) {
        onDispose {
            voskHelper?.destroy()
        }
    }

    // ── UI Layout ──────────────────────────────────────────────────
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(
                brush = Brush.verticalGradient(
                    colors = listOf(GradientStart, GradientEnd)
                )
            )
    ) {
        Scaffold(
            topBar = {
                TopAppBar(
                    title = {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                text = "提词器",
                                fontWeight = FontWeight.SemiBold,
                                fontSize = 18.sp,
                                color = Color.White
                            )
                            Spacer(Modifier.width(6.dp))
                            Text(
                                text = "离线语音",
                                fontSize = 12.sp,
                                color = Color.White.copy(alpha = 0.5f)
                            )
                        }
                    },
                    actions = {
                        if (sentences.isNotEmpty()) {
                            // Microphone status indicator
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier.padding(end = 4.dp)
                            ) {
                                val dotColor = when {
                                    modelLoading -> AmberStatus
                                    isListening -> GreenStatus
                                    else -> GreyStatus
                                }
                                Box(
                                    modifier = Modifier
                                        .size(8.dp)
                                        .clip(CircleShape)
                                        .background(dotColor)
                                )
                                Spacer(Modifier.width(4.dp))
                                val statusText = when {
                                    modelLoading -> "加载中"
                                    isListening -> "工作中"
                                    else -> "待命"
                                }
                                Text(
                                    text = statusText,
                                    fontSize = 11.sp,
                                    color = Color.White.copy(alpha = 0.6f)
                                )
                                Spacer(Modifier.width(8.dp))
                            }
                        }

                        IconButton(onClick = { showSettings = true }) {
                            Icon(
                                imageVector = Icons.Default.Settings,
                                contentDescription = "设置",
                                tint = Color.White.copy(alpha = 0.7f)
                            )
                        }
                    },
                    colors = TopAppBarDefaults.topAppBarColors(
                        containerColor = Color.Transparent
                    )
                )
            },
            floatingActionButton = {
                if (sentences.isNotEmpty()) {
                    Column(
                        horizontalAlignment = Alignment.End,
                        verticalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        // Import / Swap file
                        SmallFloatingActionButton(
                            onClick = {
                                documentPickerLauncher.launch(
                                    arrayOf(
                                        "text/plain",
                                        "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
                                        "*/*"
                                    )
                                )
                            },
                            containerColor = SurfaceDark,
                            contentColor = Color.White.copy(alpha = 0.8f),
                            shape = RoundedCornerShape(16.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Default.Add,
                                contentDescription = "换稿",
                                modifier = Modifier.size(20.dp)
                            )
                        }

                        // Start / Stop microphone
                        FloatingActionButton(
                            onClick = {
                                if (isListening) {
                                    isListening = false
                                    speechError = null
                                    voskHelper?.stopListening()
                                } else {
                                    if (!modelReady) {
                                        Toast.makeText(context, "模型加载中，请稍候", Toast.LENGTH_SHORT).show()
                                    } else if (ContextCompat.checkSelfPermission(
                                            context,
                                            Manifest.permission.RECORD_AUDIO
                                        ) == PackageManager.PERMISSION_GRANTED
                                    ) {
                                        startVoskListening()
                                    } else {
                                        micPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
                                    }
                                }
                            },
                            containerColor = if (isListening) RedAccent else AccentBlue,
                            contentColor = Color.White,
                            shape = RoundedCornerShape(20.dp)
                        ) {
                            Text(
                                text = if (isListening) "⏹" else "▶",
                                fontSize = 20.sp
                            )
                        }
                    }
                }
            },
            containerColor = Color.Transparent
        ) { paddingValues ->
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(paddingValues)
            ) {
                when {
                    sentences.isEmpty() -> {
                        // ── Empty state ──
                        Box(
                            modifier = Modifier.fillMaxSize(),
                            contentAlignment = Alignment.Center
                        ) {
                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                Text(
                                    text = "提词器",
                                    fontSize = 28.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = Color.White.copy(alpha = 0.3f)
                                )
                                Spacer(Modifier.height(16.dp))
                                Text(
                                    text = "点击下方按钮导入文档",
                                    fontSize = 14.sp,
                                    color = Color.White.copy(alpha = 0.35f)
                                )
                                Text(
                                    text = "支持 TXT 和 DOCX 格式",
                                    fontSize = 12.sp,
                                    color = Color.White.copy(alpha = 0.25f)
                                )
                                Spacer(Modifier.height(24.dp))
                                FilledTonalButton(
                                    onClick = {
                                        documentPickerLauncher.launch(
                                            arrayOf(
                                                "text/plain",
                                                "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
                                                "*/*"
                                            )
                                        )
                                    },
                                    colors = ButtonDefaults.filledTonalButtonColors(
                                        containerColor = SurfaceDark,
                                        contentColor = AccentBlue
                                    ),
                                    shape = RoundedCornerShape(14.dp)
                                ) {
                                    Text("选择文档", fontSize = 14.sp)
                                }
                            }
                        }
                    }

                    else -> {
                        // ── Main text display ──
                        LazyColumn(
                            state = listState,
                            modifier = Modifier
                                .fillMaxSize()
                                .padding(horizontal = 20.dp),
                            verticalArrangement = Arrangement.spacedBy(4.dp),
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            itemsIndexed(sentences) { index, sentence ->
                                SentenceItem(
                                    text = sentence,
                                    index = index,
                                    currentIndex = animatedIndex,
                                    baseFontSize = baseFontSize
                                )
                            }
                        }

                        // ── Recognized text overlay (fades in from bottom) ──
                        AnimatedVisibility(
                            visible = recognizedText.isNotEmpty(),
                            enter = fadeIn(animationSpec = tween(300)),
                            exit = fadeOut(animationSpec = tween(200))
                        ) {
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .background(
                                        brush = Brush.verticalGradient(
                                            colors = listOf(
                                                Color.Transparent,
                                                Color.Black.copy(alpha = 0.7f)
                                            ),
                                            startY = 0f,
                                            endY = 300f
                                        )
                                    )
                                    .padding(start = 20.dp, end = 20.dp, top = 40.dp, bottom = 24.dp)
                            ) {
                                Text(
                                    text = recognizedText,
                                    color = AccentTeal.copy(alpha = 0.8f),
                                    fontSize = 12.sp,
                                    textAlign = TextAlign.Center,
                                    lineHeight = 18.sp
                                )
                            }
                        }

                        // ── Error banner ──
                        speechError?.let { error ->
                            Snackbar(
                                modifier = Modifier
                                    .padding(horizontal = 16.dp, vertical = 8.dp),
                                containerColor = Color(0xFF37474F),
                                contentColor = Color(0xFFFFAB91),
                                shape = RoundedCornerShape(10.dp)
                            ) {
                                Text(text = error, fontSize = 12.sp)
                            }
                        }
                    }
                }
            }
        }
    }

    // ── Settings BottomSheet ──
    if (showSettings) {
        ModalBottomSheet(
            onDismissRequest = { showSettings = false },
            containerColor = Color(0xFF1E1E30),
            contentColor = Color.White,
            shape = RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp)
        ) {
            FontSettingsPanel(
                currentSize = baseFontSize,
                onSizeChange = { baseFontSize = it }
            )
        }
    }
}

// ── Sentence item with three-tier visual hierarchy ──
@Composable
private fun SentenceItem(
    text: String,
    index: Int,
    currentIndex: Float,
    baseFontSize: Int
) {
    val distance = kotlin.math.abs(index - currentIndex)
    val transitionRadius = 6f

    // Three tiers:
    // 1. Already read  (index << currentIndex) — dimmed, small
    // 2. Current       (index ≈ currentIndex) — highlighted, large, accent color
    // 3. Upcoming      (index >> currentIndex) — normal white
    val isRead = index < currentIndex - 0.5f
    val isCurrent = distance <= 1.5f

    val targetAlpha = when {
        isRead -> 0.25f
        isCurrent -> 1.0f
        else -> 0.7f
    }
    val displayAlpha by animateFloatAsState(
        targetValue = targetAlpha,
        animationSpec = tween(300)
    )

    val fontSizeScale = 1.0f + 0.5f * maxOf(0f, 1f - distance / transitionRadius)
    val fontSize = (baseFontSize * fontSizeScale).sp

    val textColor = if (isCurrent) AccentBlue else Color.White
    val fontWeight = if (isCurrent) FontWeight.SemiBold else FontWeight.Normal

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 6.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = text,
            color = textColor.copy(alpha = displayAlpha),
            fontSize = fontSize,
            fontWeight = fontWeight,
            textAlign = TextAlign.Center,
            lineHeight = (fontSize.value * 1.6f).sp,
            modifier = Modifier.padding(horizontal = 8.dp)
        )
    }
}

// ── Font settings bottom sheet ──
@Composable
private fun FontSettingsPanel(
    currentSize: Int,
    onSizeChange: (Int) -> Unit
) {
    var sliderValue by remember { mutableFloatStateOf(currentSize.toFloat()) }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 24.dp)
            .padding(bottom = 40.dp)
    ) {
        // Drag handle
        Box(
            modifier = Modifier
                .align(Alignment.CenterHorizontally)
                .width(36.dp)
                .height(4.dp)
                .clip(RoundedCornerShape(2.dp))
                .background(Color.White.copy(alpha = 0.2f))
        )
        Spacer(Modifier.height(24.dp))

        Text(
            text = "显示设置",
            fontSize = 18.sp,
            fontWeight = FontWeight.SemiBold,
            color = Color.White
        )
        Spacer(Modifier.height(20.dp))

        // Font size label row
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "字号",
                fontSize = 14.sp,
                color = Color.White.copy(alpha = 0.7f)
            )
            Text(
                text = "${sliderValue.toInt()}sp",
                fontSize = 14.sp,
                fontWeight = FontWeight.Medium,
                color = AccentBlue
            )
        }
        Spacer(Modifier.height(8.dp))

        Slider(
            value = sliderValue,
            onValueChange = { sliderValue = it },
            onValueChangeFinished = { onSizeChange(sliderValue.toInt()) },
            valueRange = 16f..48f,
            steps = 7,
            colors = SliderDefaults.colors(
                thumbColor = AccentBlue,
                activeTrackColor = AccentBlue,
                inactiveTrackColor = Color.White.copy(alpha = 0.15f)
            )
        )

        // Preview
        Spacer(Modifier.height(20.dp))
        Text(
            text = "预览",
            fontSize = 12.sp,
            color = Color.White.copy(alpha = 0.4f)
        )
        Spacer(Modifier.height(8.dp))
        Text(
            text = "欢迎使用提词器，您可以在此预览当前字体大小效果。",
            fontSize = sliderValue.sp * 0.6f,
            color = Color.White.copy(alpha = 0.6f),
            textAlign = TextAlign.Center,
            modifier = Modifier
                .fillMaxWidth()
                .background(
                    color = Color.White.copy(alpha = 0.05f),
                    shape = RoundedCornerShape(12.dp)
                )
                .padding(16.dp)
        )
    }
}
