package com.aetheria.vance.ui

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.util.Log
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.core.view.WindowCompat
import com.aetheria.vance.core.VanceCoreService
import com.aetheria.vance.shizuku.ShizukuBridge
import com.aetheria.vance.ui.theme.CipherTheme
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject

@AndroidEntryPoint
class MainActivity : ComponentActivity() {

    companion object {
        private const val TAG = "MainActivity"
    }

    @Inject lateinit var shizukuBridge: ShizukuBridge

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
        WindowCompat.setDecorFitsSystemWindows(window, false)

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

        // Initialize Shizuku and show smoke test result
        initializeShizuku()

        // Start VanceCoreService
        startVanceCoreService()
    }

    private fun checkAndRequestPermissions() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
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
        Log.d(TAG, "All permissions checked")
    }

    private fun initializeShizuku() {
        try {
            shizukuBridge.initialize()
            val available = shizukuBridge.isAvailable()
            val message = if (available) "Shizuku: connected" else "Shizuku: not found"
            Log.d(TAG, "Shizuku smoke test: $available")

            runOnUiThread {
                Toast.makeText(this, message, Toast.LENGTH_LONG).show()
            }
        } catch (e: Exception) {
            Log.e(TAG, "Shizuku initialization failed", e)
            runOnUiThread {
                Toast.makeText(this, "Shizuku: error - ${e.message}", Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun startVanceCoreService() {
        val intent = Intent(this, VanceCoreService::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(intent)
        } else {
            startService(intent)
        }
        Log.d(TAG, "VanceCoreService started")
    }

    override fun onDestroy() {
        super.onDestroy()
        try {
            shizukuBridge.destroy()
        } catch (e: Exception) {
            Log.e(TAG, "Error destroying ShizukuBridge", e)
        }
    }
}

@Composable
fun CipherApp(showChat: Boolean = false) {
    Box(modifier = Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.navigationBars)) {
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

// WelcomeScreen is now handled by OnboardingActivity
// ChatScreen and ChatBubble moved to VanceChatActivity
