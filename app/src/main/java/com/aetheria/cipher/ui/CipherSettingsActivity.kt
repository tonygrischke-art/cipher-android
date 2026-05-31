package com.aetheria.cipher.ui

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.Environment
import android.provider.Settings
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
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
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.aetheria.cipher.context.MemoryStore
import com.aetheria.cipher.ui.theme.CipherTheme
import dagger.hilt.android.AndroidEntryPoint
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import javax.inject.Inject

@AndroidEntryPoint
class CipherSettingsActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
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
    var picovoiceAccessKey by mutableStateOf("")

    // Capability toggles
    var smsEnabled by mutableStateOf(true)
    var callsEnabled by mutableStateOf(true)
    var accessibilityEnabled by mutableStateOf(true)
    var locationEnabled by mutableStateOf(true)
    var calendarEnabled by mutableStateOf(true)
    var cameraEnabled by mutableStateOf(true)

    // Version info
    val appVersion = "0.1.0"
    val buildCommit = try {
        com.aetheria.cipher.BuildConfig::class.java
        "unknown"
    } catch (e: Exception) { "dev" }

    init {
        loadPreferences()
    }

    private fun loadPreferences() {
        viewModelScope.launch {
            groqApiKey = memoryStore.getPreference("groq_api_key") ?: ""
            picovoiceAccessKey = memoryStore.getPreference("picovoice_access_key") ?: ""
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

    fun exportConversationHistory(): File? {
        return try {
            val conversations = memoryStore.getRecentExceptions(1000)
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
    var showClearConfirmDialog by remember { mutableStateOf(false) }
    var groqKeyVisible by remember { mutableStateOf(false) }
    var picovoiceKeyVisible by remember { mutableStateOf(false) }

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
                Icon(Icons.Default.ArrowBack, contentDescription = "Back")
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
                        Icon(Icons.Default.Visibility, contentDescription = "Toggle visibility")
                    }
                }
            )

            Spacer(modifier = Modifier.height(12.dp))

            OutlinedTextField(
                value = viewModel.picovoiceAccessKey,
                onValueChange = {
                    viewModel.picovoiceAccessKey = it
                    viewModel.savePreference("picovoice_access_key", it)
                },
                label = { Text("Picovoice Access Key") },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                visualTransformation = if (picovoiceKeyVisible) {
                    androidx.compose.ui.text.input.VisualTransformation.None
                } else {
                    PasswordVisualTransformation()
                },
                trailingIcon = {
                    IconButton(onClick = { picovoiceKeyVisible = !picovoiceKeyVisible }) {
                        Icon(Icons.Default.Visibility, contentDescription = "Toggle visibility")
                    }
                }
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
                Icon(Icons.Default.DeleteForever, contentDescription = null)
                Spacer(modifier = Modifier.width(8.dp))
                Text("Clear Conversation History")
            }

            Spacer(modifier = Modifier.height(8.dp))

            OutlinedButton(
                onClick = {
                    val file = viewModel.exportConversationHistory()
                    if (file != null) {
                        Toast.makeText(context, "Exported to ${file.name}", Toast.LENGTH_LONG).show()
                    } else {
                        Toast.makeText(context, "Export failed", Toast.LENGTH_SHORT).show()
                    }
                },
                modifier = Modifier.fillMaxWidth()
            ) {
                Icon(Icons.Default.FileDownload, contentDescription = null)
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
