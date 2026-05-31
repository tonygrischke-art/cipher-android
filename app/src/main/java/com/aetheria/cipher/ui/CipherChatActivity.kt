package com.aetheria.cipher.ui

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.animation.*
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.aetheria.cipher.core.CipherCoreService
import com.aetheria.cipher.context.MemoryStore
import com.aetheria.cipher.ui.theme.CipherTheme
import com.aetheria.cipher.voice.VoicePipeline
import dagger.hilt.android.AndroidEntryPoint
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

@AndroidEntryPoint
class CipherChatActivity : ComponentActivity() {

    companion object {
        private const val TAG = "CipherChat"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            CipherTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    CipherChatScreen()
                }
            }
        }
    }
}

@HiltViewModel
class CipherChatViewModel @Inject constructor(
    private val memoryStore: MemoryStore,
    @ApplicationContext private val appContext: Context
) : ViewModel() {

    private val _messages = MutableStateFlow<List<ChatMessage>>(emptyList())
    val messages: StateFlow<List<ChatMessage>> = _messages.asStateFlow()

    private val _isListening = MutableStateFlow(false)
    val isListening: StateFlow<Boolean> = _isListening.asStateFlow()

    private val _isThinking = MutableStateFlow(false)
    val isThinking: StateFlow<Boolean> = _isThinking.asStateFlow()

    private val _isSpeaking = MutableStateFlow(false)
    val isSpeaking: StateFlow<Boolean> = _isSpeaking.asStateFlow()

    init {
        loadHistory()
    }

    private fun loadHistory() {
        viewModelScope.launch {
            val recent = memoryStore.getRecentExchanges(50)
            val msgs = recent.map { entity ->
                ChatMessage(
                    text = entity.content,
                    isUser = entity.role == "user",
                    isToolOutput = false,
                    timestamp = entity.timestamp
                )
            }
            _messages.value = msgs
        }
    }

    fun sendMessage(text: String) {
        if (text.isBlank()) return

        // Add user message
        val userMsg = ChatMessage(text = text, isUser = true, isToolOutput = false)
        _messages.value = _messages.value + userMsg

        // Process via CipherCoreService
        _isThinking.value = true
        try {
            val intent = Intent(appContext, CipherCoreService::class.java).apply {
                action = CipherCoreService.ACTION_PROCESS_TRANSCRIPT
                putExtra("transcript", text)
            }
            appContext.startService(intent)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to send message", e)
            _isThinking.value = false
        }
    }

    fun onServiceResponse(response: String, actionType: String? = null) {
        _isThinking.value = false
        _isSpeaking.value = true

        val cipherMsg = ChatMessage(
            text = response,
            isUser = false,
            isToolOutput = false
        )
        _messages.value = _messages.value + cipherMsg

        if (actionType != null) {
            val toolMsg = ChatMessage(
                text = "Action: $actionType",
                isUser = false,
                isToolOutput = true
            )
            _messages.value = _messages.value + toolMsg
        }

        viewModelScope.launch {
            delay(100)
            _isSpeaking.value = false
        }
    }

    fun clearHistory() {
        viewModelScope.launch {
            memoryStore.clearHistory("default")
            _messages.value = emptyList()
        }
    }
}

@Composable
fun CipherChatScreen() {
    val viewModel: CipherChatViewModel = hiltViewModel()
    val messages by viewModel.messages.collectAsState()
    val isListening by viewModel.isListening.collectAsState()
    val isThinking by viewModel.isThinking.collectAsState()
    val isSpeaking by viewModel.isSpeaking.collectAsState()
    var inputText by remember { mutableStateOf("") }
    val listState = rememberLazyListState()
    val clipboardManager = LocalClipboardManager.current

    // Auto-scroll to bottom
    LaunchedEffect(messages.size) {
        if (messages.isNotEmpty()) {
            listState.animateScrollToItem(messages.size - 1)
        }
    }

    Column(modifier = Modifier.fillMaxSize()) {
        // Top bar
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 12.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "Cipher",
                fontSize = 20.sp,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.primary
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                // State indicator
                val stateColor = when {
                    isListening -> Color(0xFF00DAC6)
                    isThinking -> Color(0xFFFFA000)
                    isSpeaking -> Color(0xFF4CAF50)
                    else -> MaterialTheme.colorScheme.onSurfaceVariant
                }
                Box(
                    modifier = Modifier
                        .size(8.dp)
                        .clip(CircleShape)
                        .background(stateColor)
                )
                IconButton(onClick = { /* Open settings */ }) {
                    Icon(Icons.Default.Settings, contentDescription = "Settings")
                }
            }
        }

        HorizontalDivider()

        // Message history
        LazyColumn(
            state = listState,
            modifier = Modifier.weight(1f),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            items(messages, key = { it.timestamp }) { msg ->
                ChatBubble(
                    message = msg,
                    onCopy = { clipboardManager.setText(AnnotatedString(msg.text)) }
                )
            }

            if (isThinking) {
                item {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.Start
                    ) {
                        ThinkingIndicator()
                    }
                }
            }
        }

        HorizontalDivider()

        // Quick action chips
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            AssistChip(
                onClick = { viewModel.sendMessage("Give me a morning briefing") },
                label = { Text("Morning briefing", fontSize = 12.sp) }
            )
            AssistChip(
                onClick = { viewModel.sendMessage("What's my battery level?") },
                label = { Text("Battery status", fontSize = 12.sp) }
            )
            AssistChip(
                onClick = { viewModel.sendMessage("What's on my screen?") },
                label = { Text("Screen read", fontSize = 12.sp) }
            )
        }

        // Voice waveform during listening
        if (isListening) {
            VoiceWaveform(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(40.dp)
                    .padding(horizontal = 16.dp)
            )
        }

        // Input bar
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            OutlinedTextField(
                value = inputText,
                onValueChange = { inputText = it },
                modifier = Modifier.weight(1f),
                placeholder = { Text("Say something...") },
                shape = RoundedCornerShape(24.dp),
                singleLine = true
            )
            Spacer(modifier = Modifier.width(8.dp))
            FilledIconButton(
                onClick = {
                    if (inputText.isNotBlank()) {
                        viewModel.sendMessage(inputText)
                        inputText = ""
                    }
                }
            ) {
                Icon(Icons.Default.Send, contentDescription = "Send")
            }
        }
    }
}

@Composable
fun ChatBubble(
    message: ChatMessage,
    onCopy: () -> Unit = {}
) {
    val bgColor = if (message.isUser) {
        MaterialTheme.colorScheme.primary
    } else if (message.isToolOutput) {
        MaterialTheme.colorScheme.tertiaryContainer
    } else {
        MaterialTheme.colorScheme.surfaceVariant
    }
    val textColor = if (message.isUser) {
        MaterialTheme.colorScheme.onPrimary
    } else if (message.isToolOutput) {
        MaterialTheme.colorScheme.onTertiaryContainer
    } else {
        MaterialTheme.colorScheme.onSurfaceVariant
    }
    val alignment = if (message.isUser) Arrangement.End else Arrangement.Start

    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = alignment
    ) {
        if (!message.isUser) {
            // Cipher avatar
            Box(
                modifier = Modifier
                    .size(32.dp)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.primary),
                contentAlignment = Alignment.Center
            ) {
                Text("C", color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.Bold)
            }
            Spacer(modifier = Modifier.width(8.dp))
        }

        Column {
            Box(
                modifier = Modifier
                    .widthIn(max = 280.dp)
                    .clip(RoundedCornerShape(16.dp))
                    .background(bgColor)
                    .clickable(enabled = message.isToolOutput) { onCopy() }
                    .padding(12.dp)
            ) {
                if (message.isToolOutput) {
                    Column {
                        Text(
                            text = message.text,
                            color = textColor,
                            fontFamily = FontFamily.Monospace,
                            fontSize = 12.sp
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = "Tap to copy",
                            color = textColor.copy(alpha = 0.5f),
                            fontSize = 10.sp
                        )
                    }
                } else {
                    Text(text = message.text, color = textColor)
                }
            }
        }
    }
}

@Composable
fun ThinkingIndicator() {
    val infiniteTransition = rememberInfiniteTransition(label = "thinking")
    Row(
        modifier = Modifier
            .clip(RoundedCornerShape(16.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .padding(12.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        repeat(3) { i ->
            val alpha by infiniteTransition.animateFloat(
                initialValue = 0.3f,
                targetValue = 1f,
                animationSpec = infiniteRepeatable(
                    animation = tween(600, delayMillis = i * 200),
                    repeatMode = RepeatMode.Reverse
                ),
                label = "dot_$i"
            )
            Box(
                modifier = Modifier
                    .size(8.dp)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.primary.copy(alpha = alpha))
            )
        }
    }
}

@Composable
fun VoiceWaveform(modifier: Modifier = Modifier) {
    val infiniteTransition = rememberInfiniteTransition(label = "waveform")
    Row(
        modifier = modifier,
        horizontalArrangement = Arrangement.SpaceEvenly,
        verticalAlignment = Alignment.CenterVertically
    ) {
        repeat(7) { i ->
            val height by infiniteTransition.animateFloat(
                initialValue = 4f,
                targetValue = 30f,
                animationSpec = infiniteRepeatable(
                    animation = tween(500, delayMillis = i * 70),
                    repeatMode = RepeatMode.Reverse
                ),
                label = "bar_$i"
            )
            Box(
                modifier = Modifier
                    .width(4.dp)
                    .height(height.dp)
                    .clip(RoundedCornerShape(2.dp))
                    .background(MaterialTheme.colorScheme.primary)
            )
        }
    }
}

data class ChatMessage(
    val text: String,
    val isUser: Boolean = false,
    val isToolOutput: Boolean = false,
    val timestamp: Long = System.currentTimeMillis()
)
