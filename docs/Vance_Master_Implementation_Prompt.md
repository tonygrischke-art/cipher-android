# Vance AI Companion — Master Implementation Prompt

**Repository:** https://github.com/tonygrischke-art/cipher-android.git  
**Package:** `com.aetheria.vance`  
**Device:** Motorola Edge 2025 (MT6878 / Dimensity 7300)  
**NPU:** MediaTek Neuron Adapter (`libneuron_adapter_mgvi.so`) via NNAPI SL Shim  
**Owner:** Anthony (Tony)

---

## CRITICAL: Existing Architecture — DO NOT BREAK

The app already has a working inference stack. These files exist and function:

### Build
- `app/build.gradle.kts` — uses `com.google.mediapipe:tasks-genai:0.10.14` and `tasks-core:0.10.14`. **NO compile-time LiteRT dependency.**

### Native Layer
- `app/src/main/cpp/neuron_bridge.cpp` → builds `libneuron_bridge.so`
  - Loads `libtensorflowlite_jni.so` via `dlopen` + `dlsym`
  - Loads `libneuron_adapter_mgvi.so` from `/vendor/lib64/`
  - Populates `NnApiSLDriverImplFL5` shim
  - Exposes `nativeInit()`, `nativeInfer()`, `nativeClose()`

### Kotlin Engines
- `app/src/main/java/com/aetheria/vance/ai/NpuEngine.kt` — **Primary.** MediaPipe GenAI `LlmInference` via reflection only.
  - Model: `qwen15_abliterated_int4.tflite` (INT4 Qwen 1.5B)
  - Tokenizer: `qwen15_abliterated_tokenizer.model`
  - Config: maxTokens=512, topK=40, temp=0.8, seed=42
- `app/src/main/java/com/aetheria/vance/ai/TfliteLlmEngine.kt` — **Fallback.** Direct MediaPipe compile-time dependency.
  - Model path: `/data/local/tmp/cipher_models/`
  - Config: maxTokens=1024
- `app/src/main/java/com/aetheria/vance/ai/BrainRouter.kt` — **Routing.** Inference chain: SkillMatcher → FastLlmClient (localhost:8080) → NpuEngine → MainLlmClient (localhost:8081) → TfliteLlmEngine → Offline.

### System Prompt
Injects: battery, time, NPU model, Tony's preferences, partner Alisha, Aetheria Project brand.

### Model Storage
- `/data/local/tmp/cipher_models/` — primary (TfliteLlmEngine loads directly)
- App `filesDir` — NpuEngine legacy path
- `assets/` — unused at runtime

### Current Status
| Component | Status |
|-----------|--------|
| MT6878 NPU (`libneuron_adapter_mgvi.so`) | ✅ Loads |
| TFLite C API (`dlsym`) | ✅ Resolves |
| MediaPipe GenAI (`createFromOptions()`) | ✅ Returns |
| BrainRouter | ✅ Operational |
| RAG embeddings | ⏳ Pending |
| FloatingOrbService + overlay | ⏳ Pending |
| Skill learning loop | ⏳ Pending |

---

## PR #1: Schema + Trainer Scaffolding

### Files to Create / Modify

**MODIFY:** `app/src/main/java/com/aetheria/vance/data/db/MemoryDatabase.kt`  
**MODIFY:** `app/src/main/java/com/aetheria/vance/data/db/entity/MemoryEntity.kt`  
**MODIFY:** `app/src/main/java/com/aetheria/vance/data/db/dao/MemoryDao.kt`  
**CREATE:** `app/src/main/java/com/aetheria/vance/ai/MemoryFineTuner.kt`

### Migration 1 → 2

```sql
ALTER TABLE memory ADD COLUMN reinforcement_score INTEGER DEFAULT 0;
ALTER TABLE memory ADD COLUMN source TEXT DEFAULT 'user';  -- 'user' | 'web' | 'curriculum'

CREATE TABLE lora_checkpoints (
    id INTEGER PRIMARY KEY,
    filename TEXT NOT NULL,
    timestamp DATETIME NOT NULL,
    validation_loss REAL NOT NULL,
    is_active BOOLEAN DEFAULT FALSE
);

CREATE INDEX idx_memory_reinforcement ON memory(reinforcement_score);
```

### MemoryFineTuner.kt
```kotlin
class MemoryFineTuner(private val memoryDao: MemoryDao, private val context: Context) {

    fun buildTrainingSet(limit: Int = 1000): List<MemoryEntity> {
        // Pull last N samples WHERE reinforcement_score != 0
        // Order by abs(reinforcement_score) DESC
        // Stratify: 60% positive (score > 0), 40% negative (score < 0)
    }

    fun fineTuneLoRA() {
        // Stub: log "LoRA training started" with dataset size
        // Will wire to llama.cpp JNI in PR #8
    }

    fun validateLoRA(): Boolean {
        // Stub: return true
        // Will implement hold-out validation in PR #8
    }

    fun swapActiveLoRA(checkpointId: Int) {
        // UPDATE lora_checkpoints SET is_active = FALSE for all
        // Then SET is_active = TRUE for given id
    }

    fun enqueueSample(prompt: String, response: String, score: Int) {
        // Insert into memory table with reinforcement_score = score
        // source = 'user'
    }
}
```

### MemoryDao Additions
```kotlin
@Query("SELECT * FROM memory WHERE reinforcement_score != 0 ORDER BY abs(reinforcement_score) DESC LIMIT :limit")
suspend fun getTrainingSamples(limit: Int): List<MemoryEntity>

@Query("UPDATE memory SET reinforcement_score = :score WHERE id = :id")
suspend fun updateReinforcementScore(id: Long, score: Int)

@Query("SELECT * FROM lora_checkpoints ORDER BY timestamp DESC")
suspend fun getAllCheckpoints(): List<LoraCheckpointEntity>
```

**DO NOT implement actual llama.cpp training yet.** Only scaffolding, schema, and data layer.

---

## PR #2: Orb Feedback UI

### Files to Create / Modify

**CREATE:** `app/src/main/java/com/aetheria/vance/ui/orb/OrbUIController.kt`  
**MODIFY:** `app/src/main/java/com/aetheria/vance/service/FloatingOrbService.kt`  
**CREATE:** `app/src/main/res/anim/orb_pulse_green.xml`  
**CREATE:** `app/src/main/res/anim/orb_pulse_red.xml`  
**CREATE:** `app/src/main/res/anim/orb_fade.xml`

### Orb States
| State | Visual | Trigger |
|-------|--------|---------|
| PASSIVE | Solid white glow | Default |
| CANDIDATE | Green pulse @ 1.2s interval | Wake word detected, waiting tap |
| FALSE_POSITIVE | Solid red 1s | User rejects |
| UNCERTAIN | Orange pulse | Confidence < 0.7 |
| EXPLORE_MODE | Green outline | Toggled on |

### Gesture Map
| Gesture | Duration | Action |
|---------|----------|--------|
| Short tap | < 500ms | Confirm: white↔green pulse 3× → fade → enqueueSample(+3) → broadcast `ACTION_FEEDBACK` (label="correct") |
| Long tap | 500ms – 1.5s | Reject: white↔red pulse 3× → fade → enqueueSample(-2) → broadcast `ACTION_FEEDBACK` (label="wrong") |
| Double long tap | 2× long tap | Toggle EXPLORE_MODE |
| Triple long tap | 3× long tap | Emergency reset → broadcast `ACTION_RESET_SEED` |
| Long-press (1.5s) | > 1.5s | **Distinct from feedback** → trigger Screen OCR (PR #6) |

### Candidate Timeout
- On wake detected → enter CANDIDATE state
- Start 2s countdown
- If no tap → silent discard, revert PASSIVE
- Store sample with label="silence", score=0

### FloatingOrbService Integration
- Register `OrbUIController` as touch listener for orb overlay view
- Pass `lastPrompt` and `lastResponse` from `BrainRouter` after every inference
- Receive `ACTION_FEEDBACK` broadcasts → forward to `MemoryFineTuner.enqueueSample()`

### Animations
- Use `ObjectAnimator` for color transitions
- Pulse: scale 1.0 → 1.2 → 1.0 over 400ms, repeat 3×
- Fade: alpha 1.0 → 0.0 over 300ms after pulse

**DO NOT implement `MemoryFineTuner.enqueueSample()` body yet.** Just the call site and Intent broadcast.

---

## PR #3: Web Learning Subsystem

### Files to Create / Modify

**CREATE:** `app/src/main/java/com/aetheria/vance/ai/ScrapeWorker.kt`  
**CREATE:** `app/src/main/java/com/aetheria/vance/ai/WebKnowledge.kt`  
**CREATE:** `app/src/main/java/com/aetheria/vance/ai/WebIngestor.kt`  
**CREATE:** `app/src/main/java/com/aetheria/vance/data/db/entity/WebKnowledgeEntity.kt`  
**CREATE:** `app/src/main/java/com/aetheria/vance/data/db/dao/WebKnowledgeDao.kt`

### WebKnowledgeEntity
```kotlin
@Entity(tableName = "web_knowledge")
data class WebKnowledgeEntity(
    @PrimaryKey(autoGenerate = true) val id: Int = 0,
    val url: String,
    val content: String,           // chunked text segment
    val embedding: ByteArray,        // ML Kit TextEmbeddings, 768-dim float32 serialized
    val cosineScore: Float,
    val timestamp: Long,
    val keywords: String             // comma-separated
)
```

### ScrapeWorker (CoroutineWorker)
- **Constraints:** requires charging, battery not low, network not required
- **Input:** Read `/data/data/com.termux/files/home/.hermes/web_urls.txt` (one URL per line)
- **Per URL:**
  1. Execute shell via Shizuku: `"w3m -dump <url>"` or fallback `"curl -s <url> | lynx -stdin -dump"`
  2. Extract text content
  3. Chunk into ~512 char segments with 128 char overlap
  4. Filter: keep chunks with keyword overlap to system prompt keywords (`"Android"`, `"NPU"`, `"Aetheria"`, `"Moto"`, `"Alisha"`, `"Tony"`)
  5. For each surviving chunk: generate embedding (stub for now)
  6. Compare to average embedding of high-reinforcement memories (score > 2). If cosine similarity > 0.7, store in `web_knowledge`
- **Schedule:** Every 12 hours via WorkManager

### WebIngestor
```kotlin
class WebIngestor(private val webKnowledgeDao: WebKnowledgeDao) {
    suspend fun ingestWebContent(content: String) { /* chunk → filter → embed → score → store */ }
    suspend fun getCurriculum(limit: Int = 200): List<WebKnowledgeEntity> {
        // Return top N rows ordered by cosineScore DESC
    }
}
```

### WebKnowledgeDao
```kotlin
@Dao
interface WebKnowledgeDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(entity: WebKnowledgeEntity)

    @Query("SELECT * FROM web_knowledge ORDER BY cosine_score DESC LIMIT :limit")
    suspend fun getTopCurriculum(limit: Int): List<WebKnowledgeEntity>

    @Query("DELETE FROM web_knowledge WHERE timestamp < :cutoff")
    suspend fun pruneOlderThan(cutoff: Long)  // 30 days
}
```

**DO NOT implement real ML Kit embedding generation yet.** Stub with random `ByteArray` sized `768 * 4 = 3072` bytes.

---

## PR #4: CI + Seed Weights Download

### Files to Create / Modify

**CREATE:** `scripts/download_models.sh`  
**MODIFY:** `.github/workflows/build.yml`  
**MODIFY:** `app/build.gradle.kts`

### download_models.sh
```bash
#!/bin/bash
MODEL_DIR="/data/local/tmp/cipher_models"
mkdir -p "$MODEL_DIR"

# Base model
BASE_URL="https://huggingface.co/Qwen/Qwen2.5-1.5B-Instruct-GGUF/resolve/main/qwen2.5-1_5b-instruct-q4_k_m.gguf"
BASE_FILE="$MODEL_DIR/base.gguf"

# Wake word seed
WAKE_URL="https://huggingface.co/google/speech_commands_v3/resolve/main/speech_commands_v3.tflite"
WAKE_FILE="$MODEL_DIR/wake_word_seed.tflite"

# Download with resume, verify size
curl -L -C - "$BASE_URL" -o "$BASE_FILE"
curl -L -C - "$WAKE_URL" -o "$WAKE_FILE"

# Verify sizes
BASE_SIZE=$(stat -f%z "$BASE_FILE" 2>/dev/null || stat -c%s "$BASE_FILE")
WAKE_SIZE=$(stat -f%z "$WAKE_FILE" 2>/dev/null || stat -c%s "$WAKE_FILE")

echo "base.gguf: $BASE_SIZE bytes"
echo "wake_word_seed.tflite: $WAKE_SIZE bytes"

# SHA-256
sha256sum "$BASE_FILE"
sha256sum "$WAKE_FILE"
```

### build.yml Additions
```yaml
- name: Download seed models
  run: bash scripts/download_models.sh
- name: Verify models
  run: |
    test -f /data/local/tmp/cipher_models/base.gguf
    test -f /data/local/tmp/cipher_models/wake_word_seed.tflite
- name: Build APK
  run: ./gradlew assembleDebug
```

### app/build.gradle.kts
```kotlin
// Add task
tasks.register<Exec>("packageModels") {
    commandLine("bash", "scripts/download_models.sh")
}

// Run before build
preBuild.dependsOn("packageModels")

// Build config
buildConfigField("String", "MODEL_VERSION", ""qwen2.5-1.5b-q4_k_m"")
```

### First-Launch Check (MainActivity)
```kotlin
override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    val modelDir = File("/data/local/tmp/cipher_models/")
    if (!modelDir.exists() || !File(modelDir, "base.gguf").exists()) {
        AlertDialog.Builder(this)
            .setTitle("Models Required")
            .setMessage("Push models via Termux or download manually.")
            .setPositiveButton("Instructions") { _, _ -> /* show help text */ }
            .show()
    }
}
```

**DO NOT implement automatic in-app download yet.** Just check + dialog.

---

## PR #5: Shizuku + Accessibility Scaffold

### Files to Create / Modify

**CREATE:** `app/src/main/java/com/aetheria/vance/shizuku/ShizukuManager.kt`  
**CREATE:** `app/src/main/java/com/aetheria/vance/shizuku/DeviceInfoService.kt`  
**CREATE:** `app/src/main/java/com/aetheria/vance/shizuku/ActionExecutor.kt`  
**CREATE:** `app/src/main/java/com/aetheria/vance/service/VanceAccessibilityService.kt`  
**CREATE:** `app/src/main/res/xml/accessibility_service_config.xml`

### ShizukuManager.kt
```kotlin
class ShizukuManager(private val context: Context) {
    fun bindService(): Boolean  // Bind IShizukuService
    fun isAvailable(): Boolean   // Shizuku installed + permissions granted
    fun executeShell(command: String): Pair<Int, String>  // exitCode, output
    fun executeAsUser(command: String, userId: Int = 0): Pair<Int, String>
    fun requestPermission(activity: Activity)  // Launch Shizuku permission request
}
```

### DeviceInfoService.kt
| Method | Target | Latency |
|--------|--------|---------|
| `getBatteryLevel()` | BatteryManager or `dumpsys battery` | <200ms |
| `getBatteryCurrentMa()` | `/sys/class/power_supply/battery/current_now` | <200ms |
| `getThermalZones()` | Parse `/sys/class/thermal/thermal_zone*/temp` | <200ms |
| `getHighestTemp()` | Max across all zones | <200ms |
| `getNetworkState()` | WifiManager / ConnectivityManager | <200ms |
| `getScreenBrightness()` | Settings.System.SCREEN_BRIGHTNESS | <200ms |
| `getForegroundApp()` | `dumpsys activity activities \| grep mResumedActivity` | <200ms |

### ActionExecutor.kt
| Method | Mechanism | Latency |
|--------|-----------|---------|
| `tap(x, y)` | `dispatchGesture` or `input tap` | <200ms |
| `swipe(x1,y1,x2,y2,duration)` | `dispatchGesture` | <200ms |
| `type(text)` | `AccessibilityNodeInfo` or `input text` | <200ms |
| `keyEvent(keyCode)` | `injectInputEvent` | <150ms |
| `startApp(packageName)` | `am start -n` | <300ms |
| `forceStop(packageName)` | `am force-stop` | <300ms |
| `grantPermission(pkg, perm)` | `pm grant` | <400ms |
| `toggleSetting(setting, value)` | `Settings.System.putInt` | <300ms |

### VanceAccessibilityService.kt
```kotlin
class VanceAccessibilityService : AccessibilityService() {
    override fun onAccessibilityEvent(event: AccessibilityEvent) {
        // Log window state changes, detect foreground app
    }
    override fun onServiceConnected() {
        // Register with LocalBroadcastManager
    }
    fun getRoot(): AccessibilityNodeInfo? = rootInActiveWindow
    fun performGlobal(action: Int) = performGlobalAction(action)  // BACK, HOME, RECENTS
}
```

### accessibility_service_config.xml
```xml
<accessibility-service xmlns:android="http://schemas.android.com/apk/res/android"
    android:accessibilityEventTypes="typeAllMask"
    android:canRetrieveWindowContent="true"
    android:canPerformGestures="true"
    android:settingsActivity="com.aetheria.vance.SettingsActivity"
    android:description="@string/accessibility_service_description" />
```

**Test each Shizuku call with a Toast output. DO NOT implement full UI automation yet.**

---

## PR #6: Screen OCR + Orb Integration

### Files to Create / Modify

**CREATE:** `app/src/main/java/com/aetheria/vance/vision/TextRecognizer.kt`  
**CREATE:** `app/src/main/java/com/aetheria/vance/vision/ScreenCapture.kt`  
**MODIFY:** `app/src/main/java/com/aetheria/vance/ui/orb/OrbUIController.kt`  
**CREATE:** `app/src/main/java/com/aetheria/vance/ai/MemoryEmbedder.kt`

### TextRecognizer.kt
```kotlin
class TextRecognizer(private val context: Context) {
    fun recognize(bitmap: Bitmap): List<TextBlock>  // ML Kit TextRecognition
    suspend fun recognizeAndEmbed(bitmap: Bitmap) {
        // 1. recognize()
        // 2. Clean text (strip whitespace, limit 200 chars/block)
        // 3. Generate embedding stub (random ByteArray, 3072 bytes)
        // 4. Store in memory.db with source="screen_ocr", timestamp
    }
}
```

### ScreenCapture.kt
```kotlin
class ScreenCapture(private val context: Context) {
    private var mediaProjection: MediaProjection? = null
    fun requestPermission(activity: Activity)  // MediaProjection Intent
    fun captureScreen(): Bitmap?  // Use MediaProjection + ImageReader
    // Fallback: AccessibilityNodeInfo tree traversal (text only, no visual)
}
```

### OrbUIController.kt — Add OCR Trigger
```kotlin
// Distinguish from feedback long-tap by duration:
// < 1.5s = feedback (red pulse)
// > 1.5s = OCR trigger (blue pulse → processing → green flash)

fun onLongPress(durationMs: Long) {
    when {
        durationMs < 1500 -> onLongTapFeedback()  // existing
        else -> onLongPressOCR()
    }
}

private fun onLongPressOCR() {
    // 1. Animate orb: blue pulse while processing
    // 2. ScreenCapture.captureScreen()
    // 3. TextRecognizer.recognizeAndEmbed()
    // 4. On success: green flash, Toast "Captured N blocks"
    // 5. On empty: orange pulse, Toast "No text detected"
}
```

### MemoryEmbedder.kt (Stub)
```kotlin
class MemoryEmbedder {
    fun embed(text: String): ByteArray = ByteArray(3072) { Random.nextInt().toByte() }
    fun batchEmbed(texts: List<String>): List<ByteArray> = texts.map { embed(it) }
}
```

### Permissions
- Add `FOREGROUND_SERVICE_MEDIA_PROJECTION` to manifest
- Add MediaProjection permission request in `MainActivity`

**DO NOT implement real ML Kit embedding generation yet.** Stub only.

---

## PR #7: Wake Word End-to-End

### Files to Create / Modify

**CREATE:** `app/src/main/java/com/aetheria/vance/wakeword/WakeWordService.kt`  
**CREATE:** `app/src/main/java/com/aetheria/vance/wakeword/AudioCapture.kt`  
**CREATE:** `app/src/main/java/com/aetheria/vance/wakeword/MfccExtractor.kt`  
**CREATE:** `app/src/main/java/com/aetheria/vance/wakeword/WakeWordClassifier.kt`  
**CREATE:** `app/src/main/java/com/aetheria/vance/wakeword/WakeWordTrainerWorker.kt`  
**MODIFY:** `app/src/main/assets/` — place `wake_word_seed.tflite`

### AudioCapture.kt
```kotlin
class AudioCapture {
    // AudioRecord: AudioSource.MIC, 16000Hz, CHANNEL_IN_MONO, ENCODING_PCM_16BIT
    val sampleWindow = 16384   // ~1.024s @ 16kHz
    val stepSize = 8192        // ~0.512s overlap
    fun startCapture(onChunk: (FloatArray) -> Unit)  // normalized -1.0..1.0
    fun stopCapture()
    // Save raw PCM to reference_audio BLOB ONLY if user opted in
}
```

### MfccExtractor.kt
```kotlin
class MfccExtractor {
    // Input: FloatArray[16384]
    // Output: FloatArray[3920] = 40 features × 98 time steps
    // Use TFLite Support Library or custom FFT + Mel filterbank
    // Target: <15ms per window on CPU
    fun extract(samples: FloatArray): FloatArray
}
```

### WakeWordClassifier.kt
```kotlin
class WakeWordClassifier(context: Context) {
    private val interpreter: Interpreter  // load wake_word_seed.tflite
    // Input: [1, 40, 98] float32
    // Output: [1, 3] float32 (vance / silence / noise)
    // Threshold: vance confidence > 0.85 → trigger wake
    // Latency target: <10ms per inference
    fun infer(mfcc: FloatArray): FloatArray
}
```

### WakeWordService.kt (Foreground Service)
```kotlin
class WakeWordService : Service() {
    // Notification: "Vance is listening"
    // On wake detected (confidence > 0.85):
    //   1. Stop capture briefly
    //   2. Animate orb: CANDIDATE state (green pulse)
    //   3. Start 2s timeout
    //   4. Short tap → reinforce (label="vance", score=+3)
    //   5. Long tap → reject (label="noise", score=-2)
    //   6. Timeout → label="silence", score=0
    //   7. Resume capture
}
```

### WakeWordTrainerWorker.kt (CoroutineWorker)
```kotlin
class WakeWordTrainerWorker(ctx: Context, params: WorkerParameters) : CoroutineWorker(ctx, params) {
    // Constraints: charging, battery not low, network not required
    // Schedule: Every 6 hours
    // Training loop:
    //   1. Pull last 2000 samples (stratified: 70% vance, 20% noise, 10% silence)
    //   2. Weight confirmed hits 3×
    //   3. Fine-tune classifier head only (freeze base)
    //   4. 5-fold stratified cross-validation
    //   5. If accuracy >= 88%: save to filesDir, broadcast ACTION_MODEL_UPDATED
    //   6. If < 88%: retain last known good, log failure
}
```

### Database Addition
```sql
CREATE TABLE model_lineage (
    id INTEGER PRIMARY KEY,
    parent_id INTEGER,
    filename TEXT NOT NULL,
    accuracy REAL,
    timestamp DATETIME,
    is_active BOOLEAN DEFAULT FALSE
);
```

**Use CPU training. NPU acceleration for wake word training is future work.**

---

## PR #8: NPU Genetic Ops (JNI Extension)

### Files to Create / Modify

**MODIFY:** `app/src/main/cpp/neuron_bridge.cpp`  
**MODIFY:** `app/src/main/java/com/aetheria/vance/npu/NpuBridge.kt`  
**CREATE:** `app/src/main/java/com/aetheria/vance/ai/GeneticEngine.kt`

### neuron_bridge.cpp Additions
```cpp
JNIEXPORT jboolean JNICALL
Java_com_aetheria_vance_npu_NpuBridge_nativeCrossover(
    JNIEnv* env, jclass clazz,
    jstring parentA, jstring parentB, jstring child,
    jfloat mutationRate, jfloatArray fitnessWeights)
{
    // 1. Resolve parent file paths
    // 2. Load parent LoRA weights as flat float arrays
    // 3. Weighted average using fitnessWeights
    // 4. Apply Gaussian noise scaled by mutationRate
    // 5. Write child to child path as .tflite or .bin
    // 6. Return JNI_TRUE on success
    // NOTE: CPU-based crossover for now. NPU-native GEMM is future work.
}

JNIEXPORT jstring JNICALL
Java_com_aetheria_vance_npu_NpuBridge_nativeInferThermal(
    JNIEnv* env, jclass clazz, jlong handle, jstring prompt, jint maxTemp)
{
    // 1. Read thermal zones before inference
    // 2. If any zone > maxTemp, return "THERMAL_THROTTLE"
    // 3. Otherwise run nativeInfer and return result
}

JNIEXPORT jint JNICALL
Java_com_aetheria_vance_npu_NpuBridge_nativeGetMemoryTier(
    JNIEnv* env, jclass clazz, jstring modelPath)
{
    // 1. Get file size
    // 2. <= 2MB → return 0 (SRAM_TIER)
    // 3. <= 50MB → return 1 (DDR_TIER)
    // 4. Else → return 2 (STORAGE_TIER)
}
```

### NpuBridge.kt Additions
```kotlin
class NpuBridge {
    external fun nativeCrossover(parentA: String, parentB: String, child: String,
                                 mutationRate: Float, fitnessWeights: FloatArray): Boolean
    external fun nativeInferThermal(handle: Long, prompt: String, maxTemp: Int): String
    external fun nativeGetMemoryTier(modelPath: String): Int
}
```

### GeneticEngine.kt
```kotlin
data class LoRAOrganism(
    val id: String = UUID.randomUUID().toString(),
    val filePath: String,
    var fitness: Float = 0f,
    val generation: Int = 0,
    val memoryTier: Int = 1,
    val parentIds: List<String> = emptyList(),
    val createdAt: Long = System.currentTimeMillis()
)

class GeneticEngine(private val npuBridge: NpuBridge, private val context: Context) {
    private val population = mutableListOf<LoRAOrganism>()

    fun initializePopulation(size: Int = 5) { /* create mutated copies of base LoRA */ }
    fun evaluateFitness(organism: LoRAOrganism) { /* benchmark inference + power + reinforcement */ }
    fun selectParents(): Pair<LoRAOrganism, LoRAOrganism> { /* tournament selection */ }
    fun crossover(parentA: LoRAOrganism, parentB: LoRAOrganism): LoRAOrganism { /* call nativeCrossover */ }
    fun mutate(organism: LoRAOrganism, rate: Float) { /* add noise, re-evaluate memoryTier */ }
    fun cullGeneration() { /* kill bottom 20%, spawn children if < 3 */ }
    fun evolve() { /* one generation cycle */ }
}
```

**CPU-based crossover is correct for this PR.** JNI structure must support future NPU-native replacement.

---

## PR #9: Thermal + SRAM Scheduler

### Files to Create / Modify

**CREATE:** `app/src/main/java/com/aetheria/vance/thermal/ThermalGuard.kt`  
**CREATE:** `app/src/main/java/com/aetheria/vance/thermal/SramScheduler.kt`  
**MODIFY:** `app/src/main/java/com/aetheria/vance/ai/BrainRouter.kt`  
**MODIFY:** `app/src/main/java/com/aetheria/vance/shizuku/DeviceInfoService.kt`

### ThermalGuard.kt
```kotlin
class ThermalGuard(private val deviceInfo: DeviceInfoService) {
    enum class ThermalState { COOL, WARM, HOT, CRITICAL }

    fun monitor(): Flow<ThermalState>  // Poll every 5s, emit state changes
    fun getCurrentState(): ThermalState
    fun getNpuTemp(): Int  // Highest temp in °C

    // Thresholds for MT6878:
    // COOL: < 36°C
    // WARM: 36–42°C
    // HOT: > 42°C
    // CRITICAL: > 48°C
}
```

### SramScheduler.kt
```kotlin
class SramScheduler(private val npuBridge: NpuBridge, private val thermalGuard: ThermalGuard) {
    fun loadBestGene(pool: List<LoRAOrganism>): LoRAOrganism? {
        return when (thermalGuard.getCurrentState()) {
            COOL -> pool.filter { it.memoryTier == 0 }.maxByOrNull { it.fitness }
            WARM -> pool.maxByOrNull { it.fitness }  // prefer SRAM but accept DDR
            HOT, CRITICAL -> null  // fallback to base model
        }
    }
    fun getAvailableSram(): Long = 2 * 1024 * 1024  // 2MB for MT6878
}
```

### BrainRouter.kt Modifications
```kotlin
// Before routing to NpuEngine (Tier 2):
val gene = sramScheduler.loadBestGene(geneticEngine.population)
if (gene != null) {
    npuEngine.loadLoRA(gene.filePath)  // stub for now
}
// Then proceed with inference
```

### Emergency Mode (CRITICAL)
- Stop all NPU operations
- Route to FastLlmClient (localhost:8080) or offline message
- Broadcast `ACTION_THERMAL_EMERGENCY`
- Orb: solid red, slow pulse
- Auto-recovery when temp < 45°C for 60s

**DO NOT implement dynamic LoRA loading in NpuEngine yet.** Just selection logic.

---

## PR #10: P2P Gene Transfer

### Files to Create / Modify

**CREATE:** `app/src/main/java/com/aetheria/vance/p2p/GeneSyncWorker.kt`  
**CREATE:** `app/src/main/java/com/aetheria/vance/p2p/PeerDiscovery.kt`  
**CREATE:** `app/src/main/java/com/aetheria/vance/p2p/GeneTransfer.kt`  
**MODIFY:** `app/src/main/java/com/aetheria/vance/ai/GeneticEngine.kt`

### PeerDiscovery.kt
```kotlin
class PeerDiscovery(private val context: Context) {
    val SERVICE_UUID = UUID.fromString("aetheria-vance-gene-sync")

    fun scanBluetooth(): List<Peer> {
        // BluetoothAdapter scan for devices advertising SERVICE_UUID
        // Read remote fitness signature via GATT characteristic
        // Fitness signature = SHA-256(top 3 skills + avg reinforcement + validation accuracy)
        // If remote fitness > local by > 15%, mark as candidate
    }

    fun scanWifi(): List<Peer> {
        // NSD over WiFi Direct / local hotspot fallback
    }
}
```

### GeneTransfer.kt
```kotlin
class GeneTransfer(private val context: Context) {
    private val keyStore = AndroidKeyStore  // RSA 2048, generated on first launch

    suspend fun requestGene(peer: Peer): File? {
        // 1. Connect (Bluetooth RFCOMM or TCP socket)
        // 2. Send local fitness signature
        // 3. Request LoRA file
        // 4. Receive to /data/local/tmp/vance/imports/quarantine/
        // 5. Verify: size < 50MB, SHA-256 matches
        // 6. Return File
    }

    suspend fun sendGene(peer: Peer, organism: LoRAOrganism) {
        // Reverse of above — serve local gene
    }
}
```

### GeneSyncWorker.kt (CoroutineWorker)
```kotlin
class GeneSyncWorker(ctx: Context, params: WorkerParameters) : CoroutineWorker(ctx, params) {
    // Constraints: charging, network not required
    // Schedule: Every 24h, or on-demand via Settings toggle
    override suspend fun doWork(): Result {
        // 1. Discover peers
        // 2. For each candidate: requestGene()
        // 3. Quarantine received genes for 24h
        // 4. After 24h: validation (inference benchmark + coherence)
        // 5. Pass → promote to Pool 4 → active pool if fitness justifies
        // 6. Fail → delete, log rejection
    }
}
```

### GeneticEngine.kt Additions
```kotlin
fun importGene(file: File, source: String) {
    // 1. Validate file (size, format)
    // 2. Create LoRAOrganism with fitness=0, memoryTier from nativeGetMemoryTier()
    // 3. Add to quarantine list
    // 4. Schedule validation WorkManager task for +24h
}

fun exportGene(organism: LoRAOrganism): File {
    // Return File ready for transfer
}
```

**DO NOT implement full Bluetooth GATT server yet.** Stub with discovery and file paths.

---

## Global Rules for All PRs

1. **Package:** Everything under `com.aetheria.vance`
2. **Language:** Kotlin for Android, C++ for JNI, Bash for scripts
3. **Database:** Room (SQLite), existing `memory.db` path
4. **Threading:** Coroutines + WorkManager for background, Main thread for UI only
5. **Logging:** Use Android `Log` tag `"Vance"` consistently
6. **Error Handling:** Never crash on missing files / permissions. Graceful fallback always.
7. **Permissions:** Request at runtime. Shizuku, Accessibility, MediaProjection, Bluetooth, Foreground Service.
8. **NPU Safety:** Check thermal before every NPU operation. Throttle or fallback to CPU/localhost.
9. **Battery:** Respect charging state for training. Never drain battery below 20% for background work.
10. **Privacy:** No data leaves device. Raw audio discarded after MFCC unless user opts in.

---

*Generated for Anthony. Vance — Aetheria Project.*
