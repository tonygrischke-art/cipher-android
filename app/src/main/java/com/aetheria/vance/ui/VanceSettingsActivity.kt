package com.aetheria.vance.ui

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.Environment
import android.provider.Settings
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.view.WindowCompat
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.aetheria.vance.context.MemoryStore
import com.aetheria.vance.ui.theme.CipherTheme
import dagger.hilt.android.AndroidEntryPoint
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import javax.inject.Inject

@AndroidEntryPoint
class VanceSettingsActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        setContent {
            CipherTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    CipherSettingsScreen(onBack = { finish() })
                }
            }
        }
    }
}

@HiltViewModel
class CipherSettingsViewModel @Inject constructor(
    private val memoryStore: MemoryStore
) : ViewModel() {

    var wakeWordSensitivity by mutableFloatStateOf(0.5f)
    var inferenceTier by mutableStateOf("auto")
    var groqApiKey by mutableStateOf("")

    // Capability toggles
    var smsEnabled by mutableStateOf(true)
    var callsEnabled by mutableStateOf(true)
    var accessibilityEnabled by mutableStateOf(true)
    var locationEnabled by mutableStateOf(true)
    var calendarEnabled by mutableStateOf(true)
    var cameraEnabled by mutableStateOf(true)

    // Version info
    val appVersion = "0.1.0"
    val buildCommit = "dev"

    init {
        loadPreferences()
    }

    private fun loadPreferences() {
        viewModelScope.launch {
            groqApiKey = memoryStore.getPreference("groq_api_key") ?: ""
            wakeWordSensitivity = memoryStore.getPreference("wake_word_sensitivity")?.toFloatOrNull() ?: 0.5f
            inferenceTier = memoryStore.getPreference("inference_tier") ?: "auto"
            smsEnabled = memoryStore.getPreference("cap_sms") != "false"
            callsEnabled = memoryStore.getPreference("cap_calls") != "false"
            accessibilityEnabled = memoryStore.getPreference("cap_accessibility") != "false"
            locationEnabled = memoryStore.getPreference("cap_location") != "false"
            calendarEnabled = memoryStore.getPreference("cap_calendar") != "false"
            cameraEnabled = memoryStore.getPreference("cap_camera") != "false"
        }
    }

    fun savePreference(key: String, value: String) {
        viewModelScope.launch {
            memoryStore.setPreference(key, value)
        }
    }

    fun clearConversationHistory() {
        viewModelScope.launch {
            memoryStore.clearHistory("default")
        }
    }

    suspend fun exportConversationHistory(): File? {
        return try {
            val conversations = memoryStore.getRecentConversations(1000)
            val jsonArray = JSONArray()
            conversations.forEach { entity ->
                val obj = JSONObject().apply {
                    put("role", entity.role)
                    put("content", entity.content)
                    put("timestamp", entity.timestamp)
                    put("session_id", entity.sessionId)
                    entity.actionType?.let { put("action_type", it) }
                }
                jsonArray.put(obj)
            }
            val dir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
            val file = File(dir, "cipher_conversation_${System.currentTimeMillis()}.json")
            file.writeText(jsonArray.toString(2))
            file
        } catch (e: Exception) {
            null
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CipherSettingsScreen(onBack: () -> Unit = {}) {
    val viewModel: CipherSettingsViewModel = hiltViewModel()
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var showClearConfirmDialog by remember { mutableStateOf(false) }
    var groqKeyVisible by remember { mutableStateOf(false) }

    if (showClearConfirmDialog) {
        AlertDialog(
            onDismissRequest = { showClearConfirmDialog = false },
            title = { Text("Clear History?") },
            text = { Text("This will delete all conversation history. This cannot be undone.") },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.clearConversationHistory()
                    showClearConfirmDialog = false
                    Toast.makeText(context, "History cleared", Toast.LENGTH_SHORT).show()
                }) {
                    Text("Clear", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { showClearConfirmDialog = false }) {
                    Text("Cancel")
                }
            }
        )
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .windowInsetsPadding(WindowInsets.systemBars)
            .verticalScroll(rememberScrollState())
    ) {
        // Top bar
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = onBack) {
                Icon(Icons.Default.Home, contentDescription = "Back")
            }
            Text(
                text = "Settings",
                fontSize = 20.sp,
                fontWeight = FontWeight.Bold
            )
        }

        // Wake Word Section
        SectionHeader("Wake Word")
        Column(modifier = Modifier.padding(horizontal = 16.dp)) {
            Text("Sensitivity: ${(viewModel.wakeWordSensitivity * 100).toInt()}%")
            Slider(
                value = viewModel.wakeWordSensitivity,
                onValueChange = {
                    viewModel.wakeWordSensitivity = it
                    viewModel.savePreference("wake_word_sensitivity", it.toString())
                },
                valueRange = 0f..1f
            )
        }

        Spacer(modifier = Modifier.height(16.dp))

        // Inference Section
        SectionHeader("Inference")
        Column(modifier = Modifier.padding(horizontal = 16.dp)) {
            Text("Preferred inference source")
            Spacer(modifier = Modifier.height(8.dp))
            val tiers = listOf("auto" to "Auto", "ondevice" to "On-device only", "groq" to "Groq only")
            tiers.forEach { (value, label) ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 2.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    RadioButton(
                        selected = viewModel.inferenceTier == value,
                        onClick = {
                            viewModel.inferenceTier = value
                            viewModel.savePreference("inference_tier", value)
                        }
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(label)
                }
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        // Model Status Section
        SectionHeader("Models")
        Column(modifier = Modifier.padding(horizontal = 16.dp)) {
            val modelDir = java.io.File("/data/local/tmp/cipher_models")
            val models = listOf(
                Triple("mobile_actions_q8_ekv1024.litertlm", "Action model", "✅"),
                Triple("gemma-3n-E2B-it-int4.litertlm", "Reasoning model", "✅"),
                Triple("vibethinker-1.5b.litertlm", "Coding model (VibeThinker-1.5B)", "⏳"),
                Triple("gemma-4-E2B-vision.litertlm", "Vision model (Gemma 4 E2B)", "⏳")
            )
            models.forEach { (fileName, label, defaultIcon) ->
                val file = java.io.File(modelDir, fileName)
                val (icon, status) = if (file.exists()) {
                    val sizeMB = file.length() / 1024 / 1024
                    "✅" to "loaded (${sizeMB}MB)"
                } else {
                    "⏳" to "not downloaded"
                }
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(icon, fontSize = 14.sp)
                    Spacer(modifier = Modifier.width(8.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text(label, fontSize = 13.sp, fontWeight = FontWeight.Medium)
                        Text(status, fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = "Download missing models from Settings > Download Models",
                fontSize = 11.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }

        Spacer(modifier = Modifier.height(16.dp))

        // Capabilities Section
        SectionHeader("Capabilities")
        Column(modifier = Modifier.padding(horizontal = 16.dp)) {
            CapabilityToggle("SMS Messaging", viewModel.smsEnabled) {
                viewModel.smsEnabled = it
                viewModel.savePreference("cap_sms", it.toString())
            }
            CapabilityToggle("Phone Calls", viewModel.callsEnabled) {
                viewModel.callsEnabled = it
                viewModel.savePreference("cap_calls", it.toString())
            }
            CapabilityToggle("Accessibility Service", viewModel.accessibilityEnabled) {
                viewModel.accessibilityEnabled = it
                viewModel.savePreference("cap_accessibility", it.toString())
            }
            CapabilityToggle("Location", viewModel.locationEnabled) {
                viewModel.locationEnabled = it
                viewModel.savePreference("cap_location", it.toString())
            }
            CapabilityToggle("Calendar", viewModel.calendarEnabled) {
                viewModel.calendarEnabled = it
                viewModel.savePreference("cap_calendar", it.toString())
            }
            CapabilityToggle("Camera", viewModel.cameraEnabled) {
                viewModel.cameraEnabled = it
                viewModel.savePreference("cap_camera", it.toString())
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        // API Keys Section
        SectionHeader("API Keys")
        Column(modifier = Modifier.padding(horizontal = 16.dp)) {
            Text(
                text = "Optional — for cloud features",
                fontSize = 12.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(modifier = Modifier.height(8.dp))

            OutlinedTextField(
                value = viewModel.groqApiKey,
                onValueChange = {
                    viewModel.groqApiKey = it
                    viewModel.savePreference("groq_api_key", it)
                },
                label = { Text("Groq API Key") },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                visualTransformation = if (groqKeyVisible) {
                    androidx.compose.ui.text.input.VisualTransformation.None
                } else {
                    PasswordVisualTransformation()
                },
                trailingIcon = {
                    IconButton(onClick = { groqKeyVisible = !groqKeyVisible }) {
                        Icon(Icons.Default.Lock, contentDescription = "Toggle visibility")
                    }
                }
            )

            Spacer(modifier = Modifier.height(12.dp))

            if (viewModel.groqApiKey.isBlank()) {
                Text(
                    text = "Not set — cloud fallback disabled",
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.error
                )
            } else {
                Text(
                    text = "Groq API key is set — cloud fallback enabled",
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.primary
                )
            }

            Spacer(modifier = Modifier.height(12.dp))

            Text(
                text = "Wake word detection uses on-device recognition (no API key needed).",
                fontSize = 12.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }

        Spacer(modifier = Modifier.height(16.dp))

        // Data Section
        SectionHeader("Data")
        Column(modifier = Modifier.padding(horizontal = 16.dp)) {
            OutlinedButton(
                onClick = { showClearConfirmDialog = true },
                modifier = Modifier.fillMaxWidth()
            ) {
                Icon(Icons.Default.Delete, contentDescription = null)
                Spacer(modifier = Modifier.width(8.dp))
                Text("Clear Conversation History")
            }

            Spacer(modifier = Modifier.height(8.dp))

            OutlinedButton(
                onClick = {
                    scope.launch {
                        val file = viewModel.exportConversationHistory()
                        if (file != null) {
                            Toast.makeText(context, "Exported to ${file.name}", Toast.LENGTH_LONG).show()
                        } else {
                            Toast.makeText(context, "Export failed", Toast.LENGTH_SHORT).show()
                        }
                    }
                },
                modifier = Modifier.fillMaxWidth()
            ) {
                Icon(Icons.Default.Edit, contentDescription = null)
                Spacer(modifier = Modifier.width(8.dp))
                Text("Export Conversation History")
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        // About Section
        SectionHeader("About")
        Column(modifier = Modifier.padding(horizontal = 16.dp)) {
            Text("Cipher ${viewModel.appVersion}", fontWeight = FontWeight.Medium)
            Text(
                text = "Build: ${viewModel.buildCommit}",
                fontSize = 12.sp,
                fontFamily = FontFamily.Monospace,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(modifier = Modifier.height(8.dp))
            @Suppress("DEPRECATION")
            Text(
                text = "Models:\n• mobile_actions_q8_ekv1024.litertlm (action)\n• gemma-3n-E2B-it-int4.litertlm (reasoning)",
                fontSize = 12.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                fontFamily = FontFamily.Monospace
            )
            Spacer(modifier = Modifier.height(12.dp))
            TextButton(onClick = {
                val intent = Intent(Intent.ACTION_VIEW, Uri.parse("https://github.com/aetheria-project"))
                context.startActivity(intent)
            }) {
                Text("Aetheria Project")
            }
        }

        Spacer(modifier = Modifier.height(32.dp))
    }
}

@Composable
fun SectionHeader(title: String) {
    Text(
        text = title,
        fontSize = 14.sp,
        fontWeight = FontWeight.Bold,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
    )
    HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp))
}

@Composable
fun CapabilityToggle(
    label: String,
    enabled: Boolean,
    onToggle: (Boolean) -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(label)
        Switch(checked = enabled, onCheckedChange = onToggle)
    }
}
