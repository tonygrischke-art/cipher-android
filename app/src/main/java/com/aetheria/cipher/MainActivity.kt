package com.aetheria.cipher.ui

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.hilt.navigation.compose.hiltViewModel
import com.aetheria.cipher.core.CipherCoreService
import com.aetheria.cipher.ui.theme.CipherTheme
import dagger.hilt.android.AndroidEntryPoint

@AndroidEntryPoint
class MainActivity : ComponentActivity() {

    companion object {
        private const val TAG = "MainActivity"
    }

    private val permissionQueue = mutableListOf(
        Manifest.permission.RECORD_AUDIO,
        Manifest.permission.CALL_PHONE,
        Manifest.permission.READ_CONTACTS,
        Manifest.permission.RECEIVE_SMS,
        Manifest.permission.SEND_SMS,
        Manifest.permission.READ_CALL_LOG,
        Manifest.permission.ACCESS_FINE_LOCATION,
        Manifest.permission.ACCESS_COARSE_LOCATION,
        Manifest.permission.CAMERA
    )

    private var permissionIndex = 0

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) {
            Log.d(TAG, "Permission granted")
        } else {
            Log.w(TAG, "Permission denied")
        }
        requestNextPermission()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val showChat = intent?.getBooleanExtra("show_chat", false) ?: false

        setContent {
            CipherTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    CipherApp(showChat = showChat)
                }
            }
        }

        if (!showChat) {
            checkAndRequestPermissions()
        }

        // Check Shizuku
        if (!isShizukuRunning()) {
            Log.w(TAG, "Shizuku is not running — shell commands will fail")
        }

        // Start CipherCoreService
        startCipherCoreService()
    }

    private fun checkAndRequestPermissions() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            // Overlay permission check
            if (!Settings.canDrawOverlays(this)) {
                val intent = Intent(
                    Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                    Uri.parse("package:$packageName")
                )
                startActivity(intent)
            }
        }
        requestNextPermission()
    }

    private fun requestNextPermission() {
        while (permissionIndex < permissionQueue.size) {
            val permission = permissionQueue[permissionIndex++]
            if (ContextCompat.checkSelfPermission(this, permission) != PackageManager.PERMISSION_GRANTED) {
                permissionLauncher.launch(permission)
                return
            }
        }
        // All permissions checked
        Log.d(TAG, "All permissions checked")
    }

    private fun isShizukuRunning(): Boolean {
        // TODO: Check if Shizuku service is running
        return false
    }

    private fun startCipherCoreService() {
        val intent = Intent(this, CipherCoreService::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(intent)
        } else {
            startService(intent)
        }
        Log.d(TAG, "CipherCoreService started")
    }
}

@Composable
fun CipherApp(showChat: Boolean = false) {
    if (showChat) {
        ChatScreen()
    } else {
        WelcomeScreen()
    }
}

@Composable
fun WelcomeScreen() {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Text(
            text = "Cipher",
            fontSize = 48.sp,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.primary
        )
        Text(
            text = "Your AI Agent",
            fontSize = 18.sp,
            color = MaterialTheme.colorScheme.secondary
        )
        Spacer(modifier = Modifier.height(24.dp))
        Text(
            text = "Grant permissions to begin",
            fontSize = 14.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
fun ChatScreen() {
    var inputText by remember { mutableStateOf("") }
    var messages by remember { mutableStateOf(listOf<ChatMessage>()) }
    var isListening by remember { mutableStateOf(false) }

    Column(modifier = Modifier.fillMaxSize()) {
        // Message history
        Column(
            modifier = Modifier
                .weight(1f)
                .verticalScroll(rememberScrollState())
                .padding(16.dp)
        ) {
            messages.forEach { msg ->
                ChatBubble(msg)
            }
        }

        // Quick action chips
        Row(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            AssistChip(onClick = {}, label = { Text("Morning briefing") })
            AssistChip(onClick = {}, label = { Text("What's on my calendar") })
            AssistChip(onClick = {}, label = { Text("Check CI") })
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
                shape = RoundedCornerShape(24.dp)
            )
            Spacer(modifier = Modifier.width(8.dp))
            FilledIconButton(onClick = { /* Voice input toggle */ }) {
                Text(if (isListening) "⏹" else "🎤", fontSize = 20.sp)
            }
        }
    }
}

@Composable
fun ChatBubble(message: ChatMessage) {
    val bgColor = if (message.isUser) {
        MaterialTheme.colorScheme.primary
    } else {
        MaterialTheme.colorScheme.surfaceVariant
    }
    val textColor = if (message.isUser) {
        MaterialTheme.colorScheme.onPrimary
    } else {
        MaterialTheme.colorScheme.onSurfaceVariant
    }
    val alignment = if (message.isUser) Arrangement.End else Arrangement.Start

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        horizontalArrangement = alignment
    ) {
        Box(
            modifier = Modifier
                .widthIn(max = 280.dp)
                .background(bgColor, RoundedCornerShape(16.dp))
                .padding(12.dp)
        ) {
            if (message.isToolOutput) {
                Text(
                    text = message.text,
                    color = textColor,
                    fontFamily = FontFamily.Monospace,
                    fontSize = 12.sp
                )
            } else {
                Text(text = message.text, color = textColor)
            }
        }
    }
}

data class ChatMessage(
    val text: String,
    val isUser: Boolean = false,
    val isToolOutput: Boolean = false
)
