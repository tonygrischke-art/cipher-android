package com.aetheria.vance.ui

import android.Manifest
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.util.Log
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.core.view.WindowCompat
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.aetheria.vance.core.VanceCoreService
import com.aetheria.vance.shizuku.ShizukuBridge
import com.aetheria.vance.ui.theme.CipherTheme
import dagger.hilt.android.AndroidEntryPoint
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import javax.inject.Inject

@AndroidEntryPoint
class OnboardingActivity : ComponentActivity() {

    companion object {
        private const val TAG = "Onboarding"
        private const val PREFS_NAME = "cipher_onboarding_prefs"
        private const val KEY_COMPLETED = "onboarding_completed"
    }

    private lateinit var prefs: SharedPreferences

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE)

        if (prefs.getBoolean(KEY_COMPLETED, false)) {
            proceedToMain()
            return
        }

        setContent {
            CipherTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    OnboardingFlow(
                        onComplete = { markCompleteAndProceed() },
                        onSkip = { markCompleteAndProceed() }
                    )
                }
            }
        }
    }

    private fun markCompleteAndProceed() {
        prefs.edit().putBoolean(KEY_COMPLETED, true).apply()
        proceedToMain()
    }

    private fun proceedToMain() {
        // Start VanceCoreService
        val serviceIntent = Intent(this, VanceCoreService::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(serviceIntent)
        } else {
            startService(serviceIntent)
        }
        // Start FloatingOrbService
        startService(Intent(this, FloatingOrbService::class.java))
        finish()
    }
}

@HiltViewModel
class OnboardingViewModel @Inject constructor(
    private val shizukuBridge: ShizukuBridge
) : ViewModel() {

    var currentPage by mutableIntStateOf(0)
        private set

    val permissionsGranted = mutableStateMapOf<String, Boolean>()

    var shizukuAvailable by mutableStateOf(false)
        private set

    var accessibilityEnabled by mutableStateOf(false)
        private set

    var wakeWordDetected by mutableStateOf(false)
        private set

    var groqKey by mutableStateOf("")

    val totalPages = 7

    fun nextPage() {
        if (currentPage < totalPages - 1) currentPage++
    }

    fun prevPage() {
        if (currentPage > 0) currentPage--
    }

    fun checkShizuku() {
        shizukuAvailable = try {
            shizukuBridge.isAvailable()
        } catch (e: Exception) { false }
    }

    fun updatePermission(permission: String, granted: Boolean) {
        permissionsGranted[permission] = granted
    }

    fun checkAccessibility(context: android.content.Context) {
        val enabled = Settings.Secure.getString(
            context.contentResolver,
            Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
        )
        accessibilityEnabled = enabled != null &&
            enabled.contains(context.packageName) &&
            enabled.contains("VanceAccessibilityService")
    }

    fun onWakeWordDetected() {
        wakeWordDetected = true
    }
}

@Composable
fun OnboardingFlow(
    onComplete: () -> Unit,
    onSkip: () -> Unit
) {
    val viewModel: OnboardingViewModel = hiltViewModel()

    Column(modifier = Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.navigationBars)) {
        LinearProgressIndicator(
            progress = { (viewModel.currentPage + 1).toFloat() / viewModel.totalPages },
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 24.dp, vertical = 12.dp),
            color = MaterialTheme.colorScheme.primary,
        )

        // Page content
        Box(modifier = Modifier.weight(1f)) {
            AnimatedContent(
                targetState = viewModel.currentPage,
                transitionSpec = {
                    if (targetState > initialState) {
                        slideInHorizontally { it } + fadeIn() togetherWith
                            slideOutHorizontally { -it } + fadeOut()
                    } else {
                        slideInHorizontally { -it } + fadeIn() togetherWith
                            slideOutHorizontally { it } + fadeOut()
                    }
                },
                label = "page_transition"
            ) { page ->
                when (page) {
                    0 -> WelcomePage(viewModel)
                    1 -> PermissionsPage(viewModel)
                    2 -> AccessibilityPage(viewModel)
                    3 -> ShizukuPage(viewModel)
                    4 -> WakeWordPage(viewModel)
                    5 -> ApiKeysPage(viewModel)
                    6 -> ReadyPage(viewModel, onComplete)
                }
            }
        }

        // Navigation buttons
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(24.dp),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            if (viewModel.currentPage > 0) {
                TextButton(onClick = { viewModel.prevPage() }) {
                    Text("Back")
                }
            } else {
                Spacer(modifier = Modifier.width(1.dp))
            }

            if (viewModel.currentPage < viewModel.totalPages - 1) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TextButton(onClick = onSkip) {
                        Text("Skip")
                    }
                    Button(onClick = { viewModel.nextPage() }) {
                        Text("Next")
                    }
                }
            }
        }
    }
}

// ── Page 0: Welcome ──────────────────────────────────────────────

@Composable
fun WelcomePage(viewModel: OnboardingViewModel) {
    val infiniteTransition = rememberInfiniteTransition(label = "pulse")
    val scale by infiniteTransition.animateFloat(
        initialValue = 0.95f,
        targetValue = 1.05f,
        animationSpec = infiniteRepeatable(
            animation = tween(2000, easing = EaseInOutCubic),
            repeatMode = RepeatMode.Reverse
        ),
        label = "orb_pulse"
    )

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Box(
            modifier = Modifier
                .size(120.dp)
                .scale(scale)
                .clip(CircleShape)
                .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.2f))
                .border(2.dp, MaterialTheme.colorScheme.primary, CircleShape),
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = "C",
                fontSize = 48.sp,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.primary
            )
        }

        Spacer(modifier = Modifier.height(32.dp))

        Text(
            text = "Meet Vance",
            fontSize = 32.sp,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onBackground
        )

        Text(
            text = "Your autonomous AI agent.",
            fontSize = 18.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )

        Spacer(modifier = Modifier.height(24.dp))

        Text(
            text = "Runs entirely on your phone.\nNo cloud. No compromise.",
            fontSize = 14.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
            lineHeight = 20.sp
        )
    }
}

// ── Page 1: Permissions ──────────────────────────────────────────

@Composable
fun PermissionsPage(viewModel: OnboardingViewModel) {
    val context = LocalContext.current

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        // Handled by recomposition
    }
    val permissions = listOf(
        PermissionItem(Manifest.permission.RECORD_AUDIO, "Microphone", "For voice commands", true),
        PermissionItem(Manifest.permission.CALL_PHONE, "Phone", "To make calls by voice", false),
        PermissionItem(Manifest.permission.READ_CONTACTS, "Contacts", "To find people by name", false),
        PermissionItem(Manifest.permission.SEND_SMS, "SMS", "To send texts by voice", false),
        PermissionItem(Manifest.permission.ACCESS_FINE_LOCATION, "Location", "For location-aware responses", false),
        PermissionItem(Manifest.permission.CAMERA, "Camera", "For visual tasks", false),
    )

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(
            text = "Permissions",
            fontSize = 28.sp,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.padding(bottom = 8.dp)
        )
        Text(
            text = "Vance needs these to work properly",
            fontSize = 14.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(bottom = 24.dp)
        )

        permissions.forEach { perm ->
            val granted = ContextCompat.checkSelfPermission(context, perm.permission) ==
                PackageManager.PERMISSION_GRANTED

            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 4.dp)
                    .clickable {
                        if (!granted) {
                            permissionLauncher.launch(perm.permission)
                        }
                    },
                colors = CardDefaults.cardColors(
                    containerColor = if (granted)
                        MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.3f)
                    else MaterialTheme.colorScheme.surfaceVariant
                )
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        imageVector = if (granted) Icons.Default.CheckCircle else Icons.Default.Check,
                        contentDescription = null,
                        tint = if (granted) MaterialTheme.colorScheme.primary
                               else MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(modifier = Modifier.width(12.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text(text = perm.name, fontWeight = FontWeight.Medium)
                        Text(
                            text = perm.description,
                            fontSize = 12.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    if (!granted) {
                        TextButton(onClick = { permissionLauncher.launch(perm.permission) }) {
                            Text("Grant")
                        }
                    }
                }
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        // Overlay permission
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .clickable {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                        if (!Settings.canDrawOverlays(context)) {
                            context.startActivity(Intent(
                                Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                                Uri.parse("package:${context.packageName}")
                            ))
                        }
                    }
                },
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surfaceVariant
            )
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(Icons.Default.Check, contentDescription = null)
                Spacer(modifier = Modifier.width(12.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(text = "Display Over Other Apps", fontWeight = FontWeight.Medium)
                    Text(
                        text = "For the floating orb",
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                TextButton(onClick = {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                        context.startActivity(Intent(
                            Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                            Uri.parse("package:${context.packageName}")
                        ))
                    }
                }) {
                    Text("Grant")
                }
            }
        }
    }
}

data class PermissionItem(
    val permission: String,
    val name: String,
    val description: String,
    val required: Boolean
)

// ── Page 2: Accessibility ────────────────────────────────────────

@Composable
fun AccessibilityPage(viewModel: OnboardingViewModel) {
    val context = LocalContext.current

    LaunchedEffect(Unit) {
        viewModel.checkAccessibility(context)
    }

    // Poll for accessibility enablement
    LaunchedEffect(viewModel.accessibilityEnabled) {
        if (!viewModel.accessibilityEnabled) {
            while (true) {
                delay(2000)
                viewModel.checkAccessibility(context)
                if (viewModel.accessibilityEnabled) {
                    delay(1000)
                    viewModel.nextPage()
                    break
                }
            }
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Icon(
            imageVector = Icons.Default.Check,
            contentDescription = null,
            modifier = Modifier.size(64.dp),
            tint = MaterialTheme.colorScheme.primary
        )

        Spacer(modifier = Modifier.height(24.dp))

        Text(
            text = "Accessibility",
            fontSize = 28.sp,
            fontWeight = FontWeight.Bold
        )

        Spacer(modifier = Modifier.height(16.dp))

        Text(
            text = "Vance uses Accessibility Service to:\n\n" +
                "• Read what's on your screen\n" +
                "• Tap buttons and type text for you\n" +
                "• Navigate apps by voice\n\n" +
                "This data never leaves your phone.",
            fontSize = 14.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
            lineHeight = 20.sp
        )

        Spacer(modifier = Modifier.height(32.dp))

        if (viewModel.accessibilityEnabled) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    Icons.Default.CheckCircle,
                    contentDescription = null,
                    tint = Color(0xFF4CAF50)
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text("Accessibility enabled!", color = Color(0xFF4CAF50))
            }
        } else {
            Button(onClick = {
                context.startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
            }) {
                Text("Open Accessibility Settings")
            }
        }
    }
}

// ── Page 3: Shizuku ──────────────────────────────────────────────

@Composable
fun ShizukuPage(viewModel: OnboardingViewModel) {
    val context = LocalContext.current

    LaunchedEffect(Unit) {
        viewModel.checkShizuku()
    }

    // Poll for Shizuku
    LaunchedEffect(viewModel.shizukuAvailable) {
        if (!viewModel.shizukuAvailable) {
            while (true) {
                delay(3000)
                viewModel.checkShizuku()
                if (viewModel.shizukuAvailable) {
                    delay(1000)
                    viewModel.nextPage()
                    break
                }
            }
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Icon(
            imageVector = Icons.Default.Check,
            contentDescription = null,
            modifier = Modifier.size(64.dp),
            tint = MaterialTheme.colorScheme.primary
        )

        Spacer(modifier = Modifier.height(24.dp))

        Text(
            text = "Shizuku",
            fontSize = 28.sp,
            fontWeight = FontWeight.Bold
        )

        Spacer(modifier = Modifier.height(16.dp))

        Text(
            text = "Shizuku gives Cipher elevated system access for:\n\n" +
                "• Shell commands\n" +
                "• System settings\n" +
                "• Screenshots\n\n" +
                "Optional — Vance works without it.",
            fontSize = 14.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
            lineHeight = 20.sp
        )

        Spacer(modifier = Modifier.height(32.dp))

        if (viewModel.shizukuAvailable) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.CheckCircle, contentDescription = null, tint = Color(0xFF4CAF50))
                Spacer(modifier = Modifier.width(8.dp))
                Text("Shizuku connected!", color = Color(0xFF4CAF50))
            }
        } else {
            Button(onClick = {
                try {
                    context.startActivity(Intent().apply {
                        action = "moe.shizuku.manager.LAUNCH"
                    })
                } catch (e: Exception) {
                    Toast.makeText(context, "Open Shizuku app manually", Toast.LENGTH_SHORT).show()
                }
            }) {
                Text("Open Shizuku")
            }
        }
    }
}

// ── Page 4: Wake Word ────────────────────────────────────────────

@Composable
fun WakeWordPage(viewModel: OnboardingViewModel) {
    val infiniteTransition = rememberInfiniteTransition(label = "listening")
    val alpha by infiniteTransition.animateFloat(
        initialValue = 0.3f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(1000),
            repeatMode = RepeatMode.Reverse
        ),
        label = "listening_alpha"
    )

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Box(
            modifier = Modifier
                .size(100.dp)
                .clip(CircleShape)
                .background(MaterialTheme.colorScheme.primary.copy(alpha = alpha)),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                Icons.Default.Check,
                contentDescription = null,
                modifier = Modifier.size(40.dp),
                tint = Color.White
            )
        }

        Spacer(modifier = Modifier.height(24.dp))

        Text(
            text = "Wake Word",
            fontSize = 28.sp,
            fontWeight = FontWeight.Bold
        )

        Spacer(modifier = Modifier.height(16.dp))

        Text(
            text = "Say \"Hey Jarvis\" to test your wake word",
            fontSize = 16.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center
        )

        Spacer(modifier = Modifier.height(32.dp))

        if (viewModel.wakeWordDetected) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.CheckCircle, contentDescription = null, tint = Color(0xFF4CAF50))
                Spacer(modifier = Modifier.width(8.dp))
                Text("Wake word detected!", color = Color(0xFF4CAF50))
            }
        } else {
            Text(
                text = "Listening...",
                fontSize = 14.sp,
                color = MaterialTheme.colorScheme.primary
            )
        }
    }
}

// ── Page 5: API Keys ─────────────────────────────────────────────

@Composable
fun ApiKeysPage(viewModel: OnboardingViewModel) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(
            text = "API Keys",
            fontSize = 28.sp,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.padding(bottom = 8.dp)
        )
        Text(
            text = "Optional — for cloud inference fallback",
            fontSize = 14.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(bottom = 24.dp)
        )

        OutlinedTextField(
            value = viewModel.groqKey,
            onValueChange = { viewModel.groqKey = it },
            label = { Text("Groq API Key") },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true
        )

        Spacer(modifier = Modifier.height(16.dp))

        Text(
            text = "Wake word detection is built-in and fully on-device. " +
                "No API key needed.",
            fontSize = 12.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center
        )
    }
}

// ── Page 6: Ready ────────────────────────────────────────────────

@Composable
fun ReadyPage(viewModel: OnboardingViewModel, onComplete: () -> Unit) {
    val infiniteTransition = rememberInfiniteTransition(label = "ready_pulse")
    val scale by infiniteTransition.animateFloat(
        initialValue = 0.9f,
        targetValue = 1.1f,
        animationSpec = infiniteRepeatable(
            animation = tween(1500, easing = EaseInOutCubic),
            repeatMode = RepeatMode.Reverse
        ),
        label = "ready_pulse"
    )

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Box(
            modifier = Modifier
                .size(100.dp)
                .scale(scale)
                .clip(CircleShape)
                .background(Color(0xFF4CAF50).copy(alpha = 0.2f))
                .border(2.dp, Color(0xFF4CAF50), CircleShape),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                Icons.Default.Check,
                contentDescription = null,
                modifier = Modifier.size(48.dp),
                tint = Color(0xFF4CAF50)
            )
        }

        Spacer(modifier = Modifier.height(32.dp))

        Text(
            text = "Vance is ready.",
            fontSize = 28.sp,
            fontWeight = FontWeight.Bold
        )

        Spacer(modifier = Modifier.height(16.dp))

        Text(
            text = "Voice commands • App control • Screen reading\n" +
                "SMS & calls • System settings • Shell access",
            fontSize = 14.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
            lineHeight = 20.sp
        )

        Spacer(modifier = Modifier.height(48.dp))

        Button(
            onClick = onComplete,
            modifier = Modifier
                .fillMaxWidth()
                .height(56.dp),
            shape = RoundedCornerShape(28.dp)
        ) {
            Text("Start", fontSize = 18.sp)
        }
    }
}
