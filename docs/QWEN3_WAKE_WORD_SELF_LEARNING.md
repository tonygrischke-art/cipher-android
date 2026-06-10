# QWEN3 DIRECTIVE — Self-Learning Wake Word
## Cipher Android · com.aetheria.vance

**Build via GitHub Actions only. Never on-device.**

---

## GOAL

Vance learns its own wake word from the user's voice over time. No cloud, no external service. Everything runs on MT6878 offline. The system gets smarter the more it's used — false positives get corrected, confirmed hits reinforce the model, and retraining happens automatically in the background.

---

## ARCHITECTURE

```
Mic (AudioRecord 16kHz mono)
 └─► VAD gate (energy + zero-crossing)
      └─► RingBuffer (2s rolling PCM window)
           └─► MFCC extractor (13 coeffs, 10 frames)
                └─► WakeWordClassifier.kt (TFLite)
                     ├─ CONFIRMED (>0.85) ─► wake Vance
                     ├─ CANDIDATE (0.55–0.85) ─► orb pulse → user confirm/deny
                     └─ NOISE (<0.55) ─► discard
                          │
                     WakeSampleStore (Room table in memory.db)
                          └─► WakeWordTrainer (WorkManager, 6h, charging+idle)
                               └─► updated .tflite → hot-reload classifier
```

---

## FILE LIST

- `WakeWordDetector.kt` — mic loop, VAD, ring buffer, MFCC, classify
- `VadFilter.kt` — energy + zero-crossing voice activity detection
- `MfccExtractor.kt` — FFT → mel filterbank → DCT → 13 coefficients
- `WakeWordClassifier.kt` — TFLite wrapper, hot-reloadable
- `WakeSample.kt` + `WakeSampleDao.kt` — Room entity + DAO (add to existing memory.db)
- `WakeWordTrainer.kt` — WorkManager CoroutineWorker, transfer learning, validation
- `WakeWordRepository.kt` — coordinates store, trainer, classifier lifecycle
- `assets/wake_word_seed.tflite` — seed model (Google Speech Commands base, see below)

---

## IMPLEMENTATION

### build.gradle additions
```gradle
implementation 'org.jtransforms:jtransforms:3.1'          // FFT for MFCC
implementation 'androidx.work:work-runtime-ktx:2.9.0'     // WorkManager
// TFLite already present via MediaPipe — reuse runtime, don't add duplicate dep
```

---

### VadFilter.kt
```kotlin
object VadFilter {
    private const val ENERGY_THRESHOLD = 1_500_000L
    private const val MAX_ZCR = 80

    fun isVoice(frame: ShortArray): Boolean {
        val energy = frame.sumOf { it.toLong() * it }
        val zcr = (1 until frame.size).count {
            (frame[it] >= 0) != (frame[it - 1] >= 0)
        }
        return energy > ENERGY_THRESHOLD && zcr < MAX_ZCR
    }
}
```

---

### MfccExtractor.kt
```kotlin
class MfccExtractor {
    private val numCoeffs = 13
    private val numFrames = 10
    private val frameSize = 400    // 25ms @ 16kHz
    private val hopSize = 160      // 10ms @ 16kHz
    private val numFilters = 26
    private val fft = FloatFFT_1D(frameSize.toLong())

    /** Input: 1 second of PCM (16000 samples). Output: FloatArray(130). */
    fun extract(pcm: ShortArray): FloatArray {
        val floatPcm = FloatArray(pcm.size) { pcm[it] / 32768f }
        val result = FloatArray(numFrames * numCoeffs)
        for (i in 0 until numFrames) {
            val start = i * hopSize
            val frame = FloatArray(frameSize) { j ->
                if (start + j < floatPcm.size) floatPcm[start + j] else 0f
            }
            applyHamming(frame)
            fft.realForward(frame)
            val melEnergies = applyMelFilterbank(frame)
            val dct = applyDct(melEnergies)
            dct.copyInto(result, i * numCoeffs)
        }
        return result
    }

    private fun applyHamming(frame: FloatArray) {
        for (i in frame.indices)
            frame[i] *= (0.54f - 0.46f * cos(2 * PI * i / (frame.size - 1))).toFloat()
    }

    private fun applyMelFilterbank(fftFrame: FloatArray): FloatArray {
        // Standard mel filterbank: 26 filters, 0–8000Hz
        // Implementation: compute power spectrum → apply triangular filters → log
        val power = FloatArray(frameSize / 2) { i ->
            val re = fftFrame[2 * i]; val im = fftFrame[2 * i + 1]
            re * re + im * im
        }
        return FloatArray(numFilters) { filterIdx ->
            // triangular filter centered at mel-spaced frequencies
            melFilter(power, filterIdx, frameSize, 16000)
        }
    }

    private fun applyDct(melEnergies: FloatArray): FloatArray {
        return FloatArray(numCoeffs) { n ->
            var sum = 0f
            for (m in melEnergies.indices)
                sum += melEnergies[m] * cos(PI * n * (m + 0.5) / melEnergies.size).toFloat()
            sum
        }
    }

    // melFilter and hz/mel conversion helpers — standard formulas, implement inline
}
```

---

### WakeWordClassifier.kt
```kotlin
class WakeWordClassifier(private val context: Context) {
    private val prefs = context.getSharedPreferences("wake_word", Context.MODE_PRIVATE)
    private var interpreter: Interpreter = loadInterpreter()

    private fun loadInterpreter(): Interpreter {
        val customPath = prefs.getString("model_path", null)
        return if (customPath != null && File(customPath).exists()) {
            Interpreter(File(customPath))
        } else {
            val buf = context.assets.open("wake_word_seed.tflite").readBytes()
            Interpreter(ByteBuffer.wrap(buf))
        }
    }

    /** Thread-safe hot reload after training cycle completes. */
    @Synchronized
    fun reload(newPath: String) {
        interpreter.close()
        interpreter = Interpreter(File(newPath))
        prefs.edit().putString("model_path", newPath).apply()
    }

    @Synchronized
    fun classify(mfcc: FloatArray): WakeResult {
        val input = Array(1) { mfcc }
        val output = Array(1) { FloatArray(3) } // [noise, partial, wake]
        interpreter.run(input, output)
        val score = output[0][2]
        return when {
            score > sensitivityThreshold + 0.15f -> WakeResult.CONFIRMED
            score > sensitivityThreshold         -> WakeResult.CANDIDATE
            else                                 -> WakeResult.NOISE
        }
    }

    var sensitivityThreshold: Float
        get() = prefs.getFloat("sensitivity", 0.70f)
        set(v) = prefs.edit().putFloat("sensitivity", v).apply()
}

enum class WakeResult { CONFIRMED, CANDIDATE, NOISE }
```

---

### WakeSample.kt + WakeSampleDao.kt
Add to existing `memory.db` AppDatabase — just add the entity and DAO.

```kotlin
@Entity(tableName = "wake_samples")
data class WakeSample(
    @PrimaryKey(autoGenerate = true) val id: Int = 0,
    val pcmBytes: ByteArray,           // raw PCM 16000 samples (2s window)
    val mfccBytes: ByteArray,          // serialized FloatArray(130)
    val label: Int,                    // 0=noise, 1=partial, 2=wake
    val confirmedByUser: Boolean,      // explicit user confirm = higher weight
    val confidence: Float,
    val timestamp: Long = System.currentTimeMillis()
)

@Dao
interface WakeSampleDao {
    @Query("SELECT * FROM wake_samples ORDER BY timestamp DESC")
    suspend fun getAll(): List<WakeSample>

    @Query("SELECT COUNT(*) FROM wake_samples WHERE label = :label")
    suspend fun countByLabel(label: Int): Int

    @Insert
    suspend fun insert(sample: WakeSample)

    @Query("DELETE FROM wake_samples WHERE id IN " +
           "(SELECT id FROM wake_samples ORDER BY timestamp ASC LIMIT :n)")
    suspend fun deleteOldest(n: Int)

    @Query("SELECT COUNT(*) FROM wake_samples")
    suspend fun total(): Int
}
```

Add to AppDatabase:
```kotlin
@Database(entities = [MemoryStore::class, WakeSample::class], version = 2)
```
Migration: `addTable("wake_samples")` — provide Migration(1,2).

**Sample cap: 500 total. On insert, if total > 500, delete oldest 50. Maintain label balance: target 40% wake, 20% partial, 40% noise.**

---

### Sample Collection Logic (inside WakeWordDetector)

```kotlin
when (result) {
    WakeResult.CONFIRMED -> {
        // Store as wake, trigger Vance
        storeSample(pcmWindow, mfcc, label = 2, confirmed = true, confidence)
        onWakeWord()
    }
    WakeResult.CANDIDATE -> {
        // Store tentatively, pulse orb for user feedback
        val sampleId = storeSample(pcmWindow, mfcc, label = 2, confirmed = false, confidence)
        orbView.pulseForConfirmation(
            onConfirm = { markConfirmed(sampleId) },
            onDeny    = { relabelSample(sampleId, label = 0) } // it was noise
        )
    }
    WakeResult.NOISE -> {
        // Periodically store negative samples (every 60s of confirmed silence)
        if (shouldStoreNegative()) {
            storeSample(pcmWindow, mfcc, label = 0, confirmed = true, confidence)
        }
    }
}
```

False positive correction: long-press orb → marks last triggered sample as `label=0, confirmedByUser=true`.

---

### WakeWordDetector.kt (main loop)

```kotlin
class WakeWordDetector(
    private val context: Context,
    private val classifier: WakeWordClassifier,
    private val mfccExtractor: MfccExtractor,
    private val onWakeWord: () -> Unit
) {
    private val sampleRate = 16000
    private val frameSize = 512
    private val ringBuffer = ShortArray(sampleRate * 2) // 2s rolling
    private var ringPos = 0
    private var job: Job? = null

    fun start(scope: CoroutineScope) {
        job = scope.launch(Dispatchers.IO) {
            val minBuf = AudioRecord.getMinBufferSize(sampleRate,
                AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
            val audioRecord = AudioRecord(
                MediaRecorder.AudioSource.VOICE_RECOGNITION,
                sampleRate, AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT,
                maxOf(minBuf, frameSize * 4)
            )
            val frame = ShortArray(frameSize)
            audioRecord.startRecording()
            try {
                while (isActive) {
                    audioRecord.read(frame, 0, frameSize)
                    // Feed ring buffer
                    frame.copyInto(ringBuffer, ringPos % sampleRate * 2)
                    ringPos += frameSize
                    // VAD gate — only classify if voice detected
                    if (VadFilter.isVoice(frame)) {
                        val window = get1sWindow()
                        val mfcc = mfccExtractor.extract(window)
                        val result = classifier.classify(mfcc)
                        handleResult(result, window, mfcc, classifier.sensitivityThreshold)
                    }
                }
            } finally {
                audioRecord.stop()
                audioRecord.release()
            }
        }
    }

    fun stop() { job?.cancel() }

    private fun get1sWindow(): ShortArray {
        val out = ShortArray(sampleRate)
        val start = ((ringPos - sampleRate) coerceAtLeast 0) % (sampleRate * 2)
        // copy with wrap-around from ring buffer
        return out
    }
}
```

Start in `FloatingOrbService.onCreate()`:
```kotlin
wakeWordDetector.start(lifecycleScope)
```

---

### WakeWordTrainer.kt

```kotlin
class WakeWordTrainer(ctx: Context, params: WorkerParameters)
    : CoroutineWorker(ctx, params) {

    companion object {
        const val MIN_SAMPLES = 30          // don't train with fewer than this
        const val MIN_WAKE_SAMPLES = 10     // need at least 10 positive examples
        const val VALIDATION_SPLIT = 0.2f
        const val MIN_ACCURACY = 0.88f
    }

    override suspend fun doWork(): Result = withContext(Dispatchers.Default) {
        val dao = AppDatabase.getInstance(applicationContext).wakeSampleDao()
        val all = dao.getAll()

        if (all.size < MIN_SAMPLES) return@withContext Result.success()
        if (dao.countByLabel(2) < MIN_WAKE_SAMPLES) return@withContext Result.success()

        // Split train/val
        val shuffled = all.shuffled()
        val splitIdx = (shuffled.size * (1 - VALIDATION_SPLIT)).toInt()
        val trainSet = shuffled.subList(0, splitIdx)
        val valSet = shuffled.subList(splitIdx, shuffled.size)

        // Transfer learning: load current model, retrain classifier head (last layer)
        // User-confirmed samples get 3x sample weight
        val newModelPath = applicationContext.filesDir
            .absolutePath + "/wake_word_v${System.currentTimeMillis()}.tflite"

        val accuracy = trainModel(trainSet, valSet, newModelPath)

        if (accuracy >= MIN_ACCURACY) {
            // Notify service to hot-reload
            val intent = Intent("com.aetheria.vance.WAKE_MODEL_UPDATED")
                .putExtra("model_path", newModelPath)
            applicationContext.sendBroadcast(intent)
            Result.success()
        } else {
            File(newModelPath).delete() // discard failed model
            Result.retry()
        }
    }
}
```

Register in `Application.onCreate()`:
```kotlin
WorkManager.getInstance(this).enqueueUniquePeriodicWork(
    "wake_trainer",
    ExistingPeriodicWorkPolicy.KEEP,
    PeriodicWorkRequestBuilder<WakeWordTrainer>(6, TimeUnit.HOURS)
        .setConstraints(Constraints.Builder()
            .setRequiresCharging(true)
            .setRequiresDeviceIdle(true)
            .build())
        .build()
)
```

`FloatingOrbService` registers a `BroadcastReceiver` for `WAKE_MODEL_UPDATED` and calls `classifier.reload(path)`.

---

### Seed Model

Use **Google Speech Commands TFLite** from TensorFlow Hub as the base:  
`https://tfhub.dev/google/lite-model/speech_commands/1`

- Download `lite-model_speech_commands_1.tflite`
- Rename to `wake_word_seed.tflite`
- Place in `app/src/main/assets/`
- The trainer fine-tunes its final classification head on Vance-specific samples

---

## SETTINGS UI

In Vance settings screen add `WakeWordSettingsFragment`:

| Control | Function |
|---|---|
| Wake phrase display | Shows current phrase (default: "Hey Vance") |
| Sensitivity slider | Maps 0–100 → 0.55–0.90f threshold |
| Sample counter | "Learned from N voice samples" |
| Wake / Noise ratio | Shows positive vs negative sample balance |
| Reset model | Clears wake_samples table, reverts to seed model |
| Train now | Triggers WorkManager with no constraints (dev mode) |
| False positive button | Marks last trigger as noise (also: long-press orb) |

---

## MANIFEST ADDITIONS

```xml
<uses-permission android:name="android.permission.RECORD_AUDIO"/>
```
(All others already present from FloatingOrbService work)

---

## EXECUTION ORDER

1. Add `WakeSample` entity + DAO to existing Room db, provide migration
2. Implement `VadFilter`, `MfccExtractor`
3. Add seed model to `assets/`
4. Implement `WakeWordClassifier` with hot-reload
5. Implement `WakeWordDetector` — full mic loop with VAD + sample collection
6. Implement `WakeWordTrainer` WorkManager task
7. Wire orb confirmation UI (pulse + confirm/deny gestures)
8. Register trainer in Application, hot-reload broadcast in FloatingOrbService
9. Add settings UI fragment
10. Push to GitHub — CI/CD builds

**Never build on-device.**
