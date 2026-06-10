# Vance AI Companion — Complete Architecture Summary

**Device:** Motorola Edge 2025 (MT6878 / Dimensity 7300)  
**Project:** Cipher-Android (`com.aetheria.vance`)  
**Status:** NPU pipeline operational, genetic evolution layer in design  
**Last Updated:** 2026-06-10

---

## 1. Inference Stack (Operational)

### Build-Time Dependencies
```kotlin
// app/build.gradle.kts — compile-time only
implementation("com.google.mediapipe:tasks-genai:0.10.14")
implementation("com.google.mediapipe:tasks-core:0.10.14")
// NO LiteRT 2.x at compile time. NPU is runtime-only via reflection + dlopen.
```

### Native Layer (C++ / CMake)
**File:** `app/src/main/cpp/neuron_bridge.cpp` → `libneuron_bridge.so`

| Component | Loading Method | Purpose |
|-----------|---------------|---------|
| TFLite C API | `dlopen("libtensorflowlite_jni.so")` + `dlsym` | Model / interpreter / NNAPI delegate creation |
| MediaTek Neuron Adapter | `dlopen("libneuron_adapter_mgvi.so")` from `/vendor/lib64/` | MT6878 APU driver |
| NNAPI SL Shim | `NnApiSLDriverImplFL5` wrapper | Bridges TFLite NNAPI delegate → Neuron adapter |
| Session Management | `NpuSession` struct | Holds model, interpreter, options, adapter handle |

**Initialization Flow (`nativeInit`):**
1. Load TFLite C API symbols via `dlsym`
2. Load `libneuron_adapter_mgvi.so`
3. Resolve all `ANeuralNetworks*` symbols
4. Populate NNAPI SL shim with wrapper functions
5. Create TFLite model from file
6. Create NNAPI delegate → attach to interpreter options
7. Create interpreter, allocate tensors

**JNI Exports:**
- `nativeInit(modelPath, cacheDir)` → opaque session handle (`jlong`)
- `nativeInfer(handle, prompt)` → result string
- `nativeClose(handle)` → cleanup

### Kotlin Engine Layer

#### `NpuEngine.kt` — Primary NPU Path
```kotlin
// Reflection-only — zero compile-time LiteRT dependency
Class.forName("com.google.mediapipe.tasks.genai.llminference.LlmInference")
```
- **Model:** `qwen15_abliterated_int4.tflite` (INT4 quantized Qwen 1.5B)
- **Tokenizer:** `qwen15_abliterated_tokenizer.model`
- **Config:** maxTokens=512, topK=40, temp=0.8, seed=42
- **Runtime check:** `isInitialised` flag after successful `createFromOptions()`

#### `TfliteLlmEngine.kt` — Fallback (Direct MediaPipe)
- **Model path:** `/data/local/tmp/cipher_models/` (loads directly, no copy)
- **Config:** maxTokens=1024, topK=40, temp=0.8, seed=42
- **Diagnostics:** Logs file header hex + ASCII for validation
- **Role:** Last-resort fallback in `BrainRouter` chain

#### `NpuBridge` + `npu_loader.cpp` — Legacy Stub
- `npu_loader.cpp` → `libnpu_loader.so`: Safe `dlopen` for `libvance_npu.so`
- Idempotent `nativeInit()` / `nativeRelease()`
- Currently unused by primary engines (kept for compatibility)

### Routing & Orchestration (`BrainRouter.kt`)

| Tier | Engine | Use Case | Latency Target |
|------|--------|----------|----------------|
| 0 | `SkillMatcher` | Battery, time, wifi, date, memory | <50ms |
| 1 | `FastLlmClient` | Simple queries → `localhost:8080` (Qwen 0.5B) | ~500ms |
| 2 | `NpuEngine` | Complex queries → MediaPipe GenAI on NPU | ~1–2s |
| 3 | `MainLlmClient` | CPU fallback → `localhost:8081` (Qwen 1.5B) | ~2–3s |
| 4 | `TfliteLlmEngine` | Last resort → direct MediaPipe | ~3–5s |
| — | Offline message | All failed | — |

**System Prompt:** Injects device context (battery, time, NPU model, Tony's preferences, partner Alisha, Aetheria Project brand).

### Model Storage

| Location | Purpose |
|----------|---------|
| `/data/local/tmp/cipher_models/` | Primary model directory (`TfliteLlmEngine` loads directly) |
| App `filesDir` | `NpuEngine` expects model copied here (legacy path) |
| `assets/` | Not used — models pushed at runtime |

### Key Technical Decisions

1. **No compile-time LiteRT** — avoids CI linker errors; NPU is opt-in at runtime
2. **`dlopen` + `dlsym` for everything** — TFLite C API, Neuron adapter, NNAPI SL
3. **MediaPipe GenAI as primary NPU path** — `tasks-genai:0.10.14` is the only inference AAR
4. **NNAPI SL shim (FL5)** — bridges TFLite's NNAPI delegate to MediaTek's `libneuron_adapter_mgvi.so`
5. **Local llama.cpp servers** — Fast/Main tiers run on `localhost:8080/8081` (Termux-managed)
6. **Graceful degradation** — every tier logs and falls through; final message tells user to start Termux servers

---

## 2. Self-Learning Wake Word Pipeline

### Seed Model
- **Source:** Google Speech Commands v3 TFLite (1.8MB quantized)
- **Asset path:** `app/src/main/assets/wake_word_seed.tflite`

### Database Schema (`memory.db`, Migration 1→2)
```sql
CREATE TABLE wake_samples (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    spectrogram BLOB NOT NULL,          -- MFCC features, serialized 1x40x98 float32
    reference_audio BLOB,               -- Optional: raw PCM for replay/validation
    sample_rate INTEGER NOT NULL,       -- 16000
    label TEXT NOT NULL,                -- 'vance' | 'silence' | 'noise'
    confidence REAL NOT NULL DEFAULT 0, -- Predicted probability from classifier head
    timestamp DATETIME NOT NULL DEFAULT (datetime('now')),
    FOREIGN KEY (id) REFERENCES memory(id)
);
CREATE INDEX idx_wake_samples_timestamp ON wake_samples(timestamp);
CREATE INDEX idx_wake_samples_label ON wake_samples(label);
```

### Orb Feedback
| Gesture | Visual | Backend Action |
|---------|--------|----------------|
| Short tap | Pulse white ↔ green (3×) → fade | Reinforce: weight sample 3× |
| Long tap | Pulse white ↔ red (3×) → fade | Reject: flip label to `noise` |
| No tap (2s timeout) | — | Silent discard |

### Audio Capture
- **Rate:** 16kHz, 16-bit PCM
- **Window:** 16384 samples (~1.024s)
- **Step:** 8192 samples (~0.512s overlap)
- **MFCC:** 40 features × 98 time steps

### Training Loop (`WakeWordTrainerWorker`)
- **Trigger:** WorkManager, every 6 hours while charging + idle
- **Dataset:** Last 2000 labeled samples (70% vance / 20% noise / 10% silence)
- **Method:** Fine-tune classifier head only (freeze base model weights)
- **Validation:** 5-fold stratified cross-validation, ≥88% accuracy required
- **Hot swap:** Copy validated model → app files dir, signal orb reload via `LocalBroadcastManager`

### Safety
- Fallback to last known good model
- Model lineage table in `memory.db`
- Raw audio discarded after MFCC extraction unless user opts in via first-launch dialog

---

## 3. Full Model Evolution (LLM Self-Learning)

### Feedback Taxonomy
| Action | `reinforcement_score` | Meaning |
|--------|----------------------|---------|
| Short tap orb | +3 | "Vance answered correctly" |
| Long tap orb | −2 | "Vance answered wrong" + store corrected response |
| Silent discard | +1 | "Meh" — mild prompt reinforcement |

### Training Loop
- **Engine:** llama.cpp via JNI (`FastLlmClient`, `MainLlmClient`)
- **API:** `llama_train_on_sample(prompt_tokens, response_tokens, score)` + `llama_flush_to_checkpoint(out_path)`
- **Schedule:** WorkManager every 24h while charging + idle
- **Dataset:** Last 1000 high-reinforcement samples
- **Validation:** Hold-out 20%, require validation loss < threshold
- **Checkpoints:** Up to 5 checkpoints (`vance_v1.weights` … `vance_v5.weights`), auto-revert on failure

### Model Format
- **Base:** Qwen2.5-1.5B Q4_K_M (~4.2GB)
- **Fine-tuning:** LoRA adapters (freeze base, train LoRA only, delta < 10MB)
- **Storage:** LoRA weights as BLOB in `memory.db`

### Web Learning Loop
- **Sources:** Termux `w3m` scrapes (`~/.hermes/web_urls.txt`), RSS via `newsboat`, Accessibility Service screen text
- **Filter:** Keyword overlap with system prompt ("Android", "NPU", "Aetheria", "Moto", "Alisha")
- **Embed:** ML Kit TextEmbeddings → cosine similarity > 0.7 → store as `web_knowledge`
- **Train:** High-reinforcement memories + curated web knowledge → LoRA training set

### Trial & Error (Confidence Calibration)
- Every response carries raw confidence score
- Confidence < 0.7 → orb pulses orange (uncertainty)
- **Exploration mode:** 10% of time emits novel low-confidence responses; long-press orb twice to toggle

---

## 4. Shizuku + rish Automation Capabilities

| Category | Action | Latency |
|----------|--------|---------|
| **Device Monitoring** | Battery, network, brightness, screen state | <200ms |
| **Screen OCR** | Detect foreground app + content (ML Kit) | <500ms |
| **Notifications** | Read / clear / snooze by keyword / sender | <300–400ms |
| **Input Automation** | Click / tap / drag / type anywhere | <200ms |
| **Gestures** | Swipe, scroll, dismiss overlays | <200ms |
| **Keypress** | Volume, home, back, recents | <150ms |
| **Shell** | Run any command (Termux, `su`, `am`, `pm`) | <300ms |
| **Scripted Workflows** | "Clean tmp", "compile kernel" | <2s |
| **App Control** | Kill / start / force-stop, clear cache / data | <300–500ms |
| **Permissions** | Grant / deny including `SYSTEM_ALERT_WINDOW` | <400ms |
| **Settings** | Airplane, WiFi, BT, mobile data, brightness, timeout | <200–300ms |
| **File System** | Read / write / delete / append any file | <500ms |
| **File Monitoring** | Watch downloads / logs (`FileObserver`) | <1s (poll) |
| **Contacts / Calls** | Read contacts, initiate / disconnect calls | <300–400ms |
| **SMS / USSD** | Send messages / codes | <300ms |
| **Calendar** | Read / insert events, set reminders / alarms | <300–600ms |
| **Context Awareness** | "Why is my screen red?" → OCR + status bar | <500ms |
| **Summarize Screen** | "What am I looking at?" → OCR → embed → respond | <1.5s |
| **Memory Recall** | "Who did I speak to at 3pm?" → RAG + LLM | <800ms |
| **Intent Broadcasts** | Custom intents (e.g., `vance:LEARNED_NEW_FACT`) | <100ms |
| **Self-Improvement** | Detect patterns → generate skills → deploy | Async |
| **Style Evolution** | LoRA adapters trained on interaction history | Async |

### Latency Tiers
| Tier | Latency | Examples |
|------|---------|----------|
| Instant | <200ms | Toggle settings, kill app, keypress |
| Fast | 200–800ms | Scrape notifications, click UI, read contacts |
| Complex | 800ms–3s | Screen summary, multi-step commands, web scrape → insight |
| Background | Async | LoRA fine-tuning, web learning, skill discovery |

---

## 5. Genetic / Evolutionary Layer (Upper Echelon Design)

### 5.1 Multi-Tier Genetic Pools (Mirrors BrainRouter)

| Pool | Tier | Model | Size | Evolution Rate | Selection Pressure |
|------|------|-------|------|----------------|-------------------|
| **Pool 0** | 0 | `SkillMatcher` (memetic) | ~KB | Continuous | User tap rate |
| **Pool 1** | 1 | Fast 0.5B + LoRA | ~2MB | Every 2–4h | Speed + accuracy |
| **Pool 2** | 2 | NPU 1.5B INT4 + LoRA | ~8–10MB | Every 12–24h | NPU SRAM fit, thermal efficiency |
| **Pool 3** | 3 | CPU 1.5B + LoRA | ~8–10MB | Every 3 days (backup) | Cross-pollination with Pool 2 |
| **Pool 4** | 4 | Emergency (static) | ~1MB | Never | Manual curation only |

### 5.2 NPU-Native Genetic Operations

**Crossover in NPU Memory:**
- Two parent LoRAs loaded into NPU SRAM
- `neuron_adapter` GEMM op produces child weights
- Written directly to `/data/local/tmp/vance/population/`
- Latency: ~95ms vs. ~3.8s on CPU

**Validation Gate:**
- `nativeInfer(child_handle, test_prompt)` → must complete <200ms
- Response coherence check via embedding similarity
- `neuron-compile` pre-validates gene at birth

### 5.3 Thermal-Aware Evolution (Edge 2025)

| Thermal State | NPU | CPU | Evolution Mode |
|---------------|-----|-----|----------------|
| **Cool** (<36°C, charging, night) | Full training, high mutation | Idle | **Rapid** — new gen every 6h |
| **Warm** (36–42°C, daytime) | Inference only, queue mutations | Light background | **Stasis** — store for later |
| **Hot** (>42°C) | Shutdown | Route to localhost:8080/8081 | **Hibernation** — replay only |
| **Critical** (>48°C) | All NPU killed | Minimal survival | **Emergency** — revert to seed |

**Fitness = Intelligence per Watt:**
- Score divided by average inference power draw (`/sys/class/power_supply/battery/current_now`)
- Smaller, efficient models can outcompete "smarter" heavy ones

### 5.4 SRAM-First Selection Pressure

- Genes fitting entirely in NPU SRAM (~2MB) get **10× fitness bonus**
- DDR-backed genes penalized: `score *= 0.6`
- Evolution naturally favors **sparse, pruned, dense** LoRA organisms

### 5.5 Horizontal Gene Transfer

**Bluetooth LE Peer Discovery:**
- Scan for `com.aetheria.vance` service UUID
- Exchange fitness signatures (top 3 skills + validation accuracy hash)
- If remote fitness > local by 15% → request LoRA file transfer
- **Quarantine:** Imported gene runs in Pool 4 for 24h before promotion

**Termux P2P:**
- `termux-bluetooth-scan` → `rsync` LoRA files over Bluetooth PAN
- `termux-wifi-share` → local hotspot → TCP gene transfer

### 5.6 Moto Edge 2025 Hardware Phenotypes

| Sensor / Feature | Evolutionary Pressure | Gene Expression |
|------------------|----------------------|-----------------|
| **AMOLED Peek Display** | Power efficiency | Black-background UI genes bonus; white penalized |
| **Chop Gesture** | Physical interaction | Evolves custom actions beyond flashlight |
| **Twist Gesture** | Camera integration | Twist → OCR subject, identify photographed objects |
| **Three-Finger Screenshot** | Memory capture | Auto-embed in `memory.db`; visual memory genes thrive |
| **Proximity + Light** | Context awareness | Pocket = suppress wake; dark room = dim orb |
| **Accelerometer / Gyro** | Activity inference | Walking = short responses; sitting = deep reasoning |
| **Battery Current** (Shizuku) | Energy selection | Genes spiking >500mA during inference culled |

### 5.7 Epigenetic Modulation

**Hypernetwork (tiny, few KB):**
- Generates LoRA weights on-the-fly based on context
- Tags in `memory.db`: `stress_level`, `time_of_day`, `location_context`
- "Hormonal analogues":
  - `cortisol` (high error rate) → increases learning rate temporarily
  - `dopamine` (user praise) → strengthens associated pathways for 24h
  - `serotonin` (stable positive) → reduces exploration, deepens exploitation

### 5.8 Antifragile Stress Testing

- Randomly inject stressors: corrupt 1% training data, simulate low-memory, add sensor noise
- Survivors = antifragile, get fitness bonus
- Anomalous gradient updates detected → quarantine LoRA, spawn clean backup

### 5.9 Death & Rebirth

- **Programmed senescence:** After 50 generations, force "death event" — archive lineage, restart from seed + full interaction history as curriculum
- **Fossil record:** Compress dead models to `.vance/fossils/`; occasional resurrection with modern data
- **Reincarnation:** Option to migrate evolved behavior to new base model (e.g., Qwen3 when released)

### 5.10 Emergency Brake

- 3× long-tap orb → "Reset to Vance seed weights" (copies `base.gguf`)
- Auto-revert if validation fails
- Model lineage tracked in `memory.db` (parent-child, validation metrics)

---

## 6. Proposed PR Structure

| PR | Scope | Key Files |
|----|-------|-----------|
| **PR #1** | Schema + Trainer Scaffolding | `MemoryFineTuner.kt`, migration (`reinforcement_score`, `source`, `lora_checkpoints`) |
| **PR #2** | Orb Feedback UI | `OrbUIController.kt` (tap logic, animations, color states) |
| **PR #3** | Web Learning Subsystem | `ScrapeWorker.kt`, Termux tools (`w3m`, `newsboat`), `WebKnowledge.kt` |
| **PR #4** | CI + Seed Weights | Download script for `qwen2.5-1_5b-instruct-q4_k_m.gguf`, APK packaging |
| **PR #5** | Shizuku + Accessibility Scaffold | `DeviceInfoService.kt`, `ActionExecutor.kt`, `ShizukuManager` wrapper |
| **PR #6** | Screen OCR + Orb | `TextRecognizer.kt` (ML Kit), `MediaProjection` trigger, embed cache |
| **PR #7** | Wake Word End-to-End | `WakeWordTrainerWorker.kt`, `wake_word_seed.tflite`, `wake_samples` migration |
| **PR #8** | NPU Genetic Ops (JNI) | Extend `neuron_bridge.cpp`: `nativeCrossover`, `nativeInferThermal`, `nativeGetMemoryTier` |
| **PR #9** | Thermal + SRAM Scheduler | `ThermalGuard.kt`, `SramScheduler.kt`, dynamic gene loading in `BrainRouter` |
| **PR #10** | P2P Gene Transfer | `GeneSyncWorker.kt`, Bluetooth LE discovery, quarantine logic |

---

## 7. Safety & Limits

| Limit | Value | Enforcement |
|-------|-------|-------------|
| LoRA delta cap | 50MB | Auto-prune oldest checkpoints |
| Checkpoint retention | 5 max | FIFO eviction |
| Validation accuracy (wake word) | ≥88% | Block hot-swap if below |
| Validation loss (LLM) | < threshold | Revert to last known good |
| NPU thermal ceiling | 42°C | Throttle training; 48°C = emergency shutdown |
| Battery drain (training) | <5%/hr | Pause evolution if exceeded |
| Web content | Device-only | Never leaves phone; raw audio discarded after MFCC |
| Emergency reset | 3× long-tap | Instant revert to seed weights |

---

## 8. Current Status

| Component | Status |
|-----------|--------|
| MT6878 NPU (`libneuron_adapter_mgvi.so`) | ✅ Loads successfully |
| TFLite C API (`dlsym` from `libtensorflowlite_jni.so`) | ✅ All symbols resolve |
| MediaPipe GenAI (`LlmInference.createFromOptions()`) | ✅ Returns successfully |
| `BrainRouter` inference chain | ✅ Operational |
| RAG embeddings (`MemoryEmbedder` / `MemoryRetriever`) | ⏳ Pending wiring |
| `FloatingOrbService` foreground + overlay | ⏳ Pending (`SYSTEM_ALERT_WINDOW` granted) |
| Skill learning loop (`SkillLearner`) | ⏳ Pending |
| Genetic / evolutionary layer | 📝 Design phase |
| Wake word self-learning | 📝 Design phase |

---

## 9. Build Commands (CI Only)

```bash
# GitHub Actions — no local Gradle
$ git push origin HEAD
# → watch workflow run URL
# → download app-debug.apk from artifacts
# → adb install -r app-debug.apk (after pm uninstall com.aetheria.vance)
```

---

## 10. Session Notes

- **Provider Issues:** Multiple `APITimeoutError` failures on NVIDIA endpoint (`qwen/qwen3-next-80b-a3b-instruct`, `mistralai/mistral-large-3-675b-instruct-2512`)
- **Rate Limits:** Hit HTTP 429 on Mistral after context compaction
- **Context Size:** Peaked at ~48,500 tokens across 156 messages before compression
- **User Intent:** Full autonomous evolution — wake word + LLM weights + web learning + device automation — all local, no cloud sync
- **NPU Architecture:** MediaPipe GenAI → NNAPI delegate → `libneuron_adapter_mgvi.so` → MT6878 APU. No compile-time LiteRT. All runtime via reflection + dlopen.
- **llama.cpp Servers:** Fast (0.5B) on `localhost:8080`, Main (1.5B) on `localhost:8081` — Termux-managed

---

*Generated for Anthony. Vance — Aetheria Project.*
