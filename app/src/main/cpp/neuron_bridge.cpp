// ============================================================================
// NeuronBridge — JNI bridge to MediaTek NeuroPilot Express SDK
// File: app/src/main/cpp/neuron_bridge.cpp
// Library: libneuron_bridge.so → libneuronusdk_adapter.mtk.so
//
// Lifecycle:
//   nativeInit(path)  → load DLA → compile with MDLA flags → cache → return handle
//   nativeInfer(h, prompt) → tokenize → setInput → compute → readOutput → text
//   nativeClose(h)    → free execution → free compilation → free model
//
// Caching: compiled network blob cached to {cacheDir}/neuron_cache/{modelName}.compiled
//          On subsequent launches, restored via NeuronModel_restoreFromCompiledNetwork
// ============================================================================

#include <jni.h>
#include <dlfcn.h>
#include <android/log.h>
#include <string>
#include <vector>
#include <mutex>
#include <fstream>
#include <cstring>
#include <sys/stat.h>

#include "NeuronAdapter.h"

#define LOG_TAG "NeuronBridge"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO,  LOG_TAG, __VA_ARGS__)
#define LOGD(...) __android_log_print(ANDROID_LOG_DEBUG, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

// ── Session state ──────────────────────────────────────────────────────────
struct NeuronSession {
    void* adapter_handle = nullptr;
    NeuronModel* model = nullptr;
    NeuronCompilation* compilation = nullptr;
    NeuronExecution* execution = nullptr;
    std::string model_path;
    bool from_cache = false;

    // Resolved function pointers from libneuronusdk_adapter.mtk.so
    int (*fnModelCreate)(NeuronModel**) = nullptr;
    void (*fnModelFree)(NeuronModel*) = nullptr;
    int (*fnModelFinish)(NeuronModel*) = nullptr;
    int (*fnModelAddOperand)(NeuronModel*, const NeuronOperandType*) = nullptr;
    int (*fnModelSetOperandValue)(NeuronModel*, int32_t, const void*, size_t) = nullptr;
    int (*fnModelIdentifyInputsAndOutputs)(NeuronModel*, uint32_t, const uint32_t*, uint32_t, const uint32_t*) = nullptr;
    int (*fnModelRelaxComputationFloat32toFloat16)(NeuronModel*, bool) = nullptr;
    int (*fnModelRestore)(NeuronModel**, NeuronCompilation**, const void*, const size_t) = nullptr;
    int (*fnCompilationCreate)(NeuronModel*, NeuronCompilation**) = nullptr;
    int (*fnCompilationCreateV2)(NeuronModel*, CompilationType, const char*, NeuronCompilation**) = nullptr;
    void (*fnCompilationFree)(NeuronCompilation*) = nullptr;
    int (*fnCompilationFinish)(NeuronCompilation*) = nullptr;
    int (*fnCompilationStore)(NeuronCompilation*, void*, const size_t) = nullptr;
    int (*fnCompilationGetSize)(NeuronCompilation*, size_t*) = nullptr;
    int (*fnCompilationSetOptString)(NeuronCompilation*, const char*) = nullptr;
    int (*fnExecutionCreate)(NeuronCompilation*, NeuronExecution**) = nullptr;
    void (*fnExecutionFree)(NeuronExecution*) = nullptr;
    int (*fnExecutionSetInput)(NeuronExecution*, int32_t, const NeuronOperandType*, const void*, size_t) = nullptr;
    int (*fnExecutionSetOutput)(NeuronExecution*, int32_t, const NeuronOperandType*, void*, size_t) = nullptr;
    int (*fnExecutionCompute)(NeuronExecution*) = nullptr;
};

static std::mutex g_mutex;

// ── Helper: resolve one symbol ────────────────────────────────────────────
template<typename T>
static bool sym(void* h, const char* name, T& out) {
    out = (T)dlsym(h, name);
    if (!out) { LOGE("dlsym(%s) failed: %s", name, dlerror()); return false; }
    return true;
}

// ── Helper: mkdir -p ──────────────────────────────────────────────────────
static bool ensureDir(const std::string& path) {
    struct stat st;
    if (stat(path.c_str(), &st) == 0) return S_ISDIR(st.st_mode);
    return mkdir(path.c_str(), 0755) == 0;
}

// ── Helper: extract filename from path ────────────────────────────────────
static std::string baseName(const std::string& path) {
    size_t pos = path.rfind('/');
    return (pos == std::string::npos) ? path : path.substr(pos + 1);
}

// ── Load the adapter library ───────────────────────────────────────────────
static void* loadAdapter() {
    static void* h = nullptr;
    if (h) return h;
    const char* paths[] = {
        "libneuronusdk_adapter.mtk.so",
        "/vendor/lib64/libneuronusdk_adapter.mtk.so",
        "/system/lib64/libneuronusdk_adapter.mtk.so",
    };
    for (auto p : paths) {
        h = dlopen(p, RTLD_LAZY);
        if (h) { LOGI("Loaded adapter from: %s", p); return h; }
    }
    LOGE("Cannot load libneuronusdk_adapter.mtk.so: %s", dlerror());
    return nullptr;
}

// ── Resolve all function pointers ─────────────────────────────────────────
#define RESOLVE(fn, field) if (!sym(h, fn, s->field)) return false

static bool resolveAll(NeuronSession* s) {
    void* h = s->adapter_handle;
    RESOLVE("NeuronModel_create",                          fnModelCreate);
    RESOLVE("NeuronModel_free",                            fnModelFree);
    RESOLVE("NeuronModel_finish",                          fnModelFinish);
    RESOLVE("NeuronModel_addOperand",                      fnModelAddOperand);
    RESOLVE("NeuronModel_setOperandValue",                 fnModelSetOperandValue);
    RESOLVE("NeuronModel_identifyInputsAndOutputs",        fnModelIdentifyInputsAndOutputs);
    RESOLVE("NeuronModel_relaxComputationFloat32toFloat16", fnModelRelaxComputationFloat32toFloat16);
    RESOLVE("NeuronModel_restoreFromCompiledNetwork",      fnModelRestore);
    RESOLVE("NeuronCompilation_create",                    fnCompilationCreate);
    RESOLVE("NeuronCompilation_createV2",                  fnCompilationCreateV2);
    RESOLVE("NeuronCompilation_free",                      fnCompilationFree);
    RESOLVE("NeuronCompilation_finish",                    fnCompilationFinish);
    RESOLVE("NeuronCompilation_storeCompiledNetwork",      fnCompilationStore);
    RESOLVE("NeuronCompilation_getCompiledNetworkSize",    fnCompilationGetSize);
    RESOLVE("NeuronCompilation_setOptimizationString",     fnCompilationSetOptString);
    RESOLVE("NeuronExecution_create",                      fnExecutionCreate);
    RESOLVE("NeuronExecution_free",                        fnExecutionFree);
    RESOLVE("NeuronExecution_setInput",                    fnExecutionSetInput);
    RESOLVE("NeuronExecution_setOutput",                   fnExecutionSetOutput);
    RESOLVE("NeuronExecution_compute",                     fnExecutionCompute);
    LOGI("All Neuron API symbols resolved");
    return true;
}

#undef RESOLVE

// ── Load model/DLA binary from disk ───────────────────────────────────────
static bool loadModelBinary(const std::string& path, std::vector<uint8_t>& out) {
    FILE* f = fopen(path.c_str(), "rb");
    if (!f) { LOGE("Cannot open model file: %s", path.c_str()); return false; }
    fseek(f, 0, SEEK_END);
    long size = ftell(f);
    fseek(f, 0, SEEK_SET);
    out.resize(size);
    size_t rd = fread(out.data(), 1, size, f);
    fclose(f);
    if ((long)rd != size) { LOGE("Short read: %zu/%ld", rd, size); return false; }
    LOGI("Loaded model binary: %ld bytes from %s", size, path.c_str());
    return true;
}

// ── Build a minimal NeuronModel from a pre-built model binary ─────────────
// The model binary (.tflite / .litertlm / .dla) is a flatbuffer.
// We construct a minimal graph: input → [model weights] → output
static bool buildModel(NeuronSession* s, const std::vector<uint8_t>& model_data) {
    int ret;

    // Create empty model
    ret = s->fnModelCreate(&s->model);
    if (ret != NEURON_NO_ERROR || !s->model) {
        LOGE("NeuronModel_create failed: %d", ret);
        return false;
    }

    // Operand 0: input tensor (int32 token IDs, batch=1, seq=512)
    uint32_t in_dims[] = {1, 512};
    NeuronOperandType input_type = {};
    input_type.type = NEURON_TENSOR_INT32;
    input_type.dimensionCount = 2;
    input_type.dimensions = in_dims;

    ret = s->fnModelAddOperand(s->model, &input_type);
    if (ret != NEURON_NO_ERROR) { LOGE("addOperand(input) failed: %d", ret); return false; }

    // Operand 1: output tensor (int32 token IDs, batch=1, seq=512)
    uint32_t out_dims[] = {1, 512};
    NeuronOperandType output_type = {};
    output_type.type = NEURON_TENSOR_INT32;
    output_type.dimensionCount = 2;
    output_type.dimensions = out_dims;

    ret = s->fnModelAddOperand(s->model, &output_type);
    if (ret != NEURON_NO_ERROR) { LOGE("addOperand(output) failed: %d", ret); return false; }

    // Operand 2: constant weight from model binary
    uint32_t weight_dims[] = {1, (uint32_t)(model_data.size() / sizeof(float))};
    NeuronOperandType weight_type = {};
    weight_type.type = NEURON_TENSOR_FLOAT32;
    weight_type.dimensionCount = 2;
    weight_type.dimensions = weight_dims;

    ret = s->fnModelAddOperand(s->model, &weight_type);
    if (ret != NEURON_NO_ERROR) { LOGE("addOperand(weight) failed: %d", ret); return false; }

    // Set model data as the weight operand
    ret = s->fnModelSetOperandValue(s->model, 2, model_data.data(), model_data.size());
    if (ret != NEURON_NO_ERROR) {
        LOGE("setOperandValue(weight) failed: %d (size=%zu)", ret, model_data.size());
        return false;
    }

    // Identify inputs and outputs
    uint32_t inputs[] = {0};
    uint32_t outputs[] = {1};
    ret = s->fnModelIdentifyInputsAndOutputs(s->model, 1, inputs, 1, outputs);
    if (ret != NEURON_NO_ERROR) { LOGE("identifyInputsAndOutputs failed: %d", ret); return false; }

    // Allow FP16 computation for better NPU throughput
    s->fnModelRelaxComputationFloat32toFloat16(s->model, true);

    // Finalize model
    ret = s->fnModelFinish(s->model);
    if (ret != NEURON_NO_ERROR) { LOGE("NeuronModel_finish failed: %d", ret); return false; }

    LOGI("Model built from binary (%zu bytes)", model_data.size());
    return true;
}

// ── Compile model with MDLA optimization flags ─────────────────────────────
static bool compileModel(NeuronSession* s) {
    static const char* OPT_FLAGS =
        "--relax-fp32 "
        "--opt 3 "
        "--opt-footprint "
        "--opt-accuracy "
        "--mdla-mlo "
        "--mdla-conv-exp 1 "
        "--mem-opt 3 "
        "--stable-linearize "
        "--l1-size-kb 7168 "
        "--num-mdla 4 "
        "--mdla-flash-attention-mode 0 "
        "--fc-to-conv "
        "--mdla-broadcast-act-wgt 1 "
        "--broadcast-flow-distance 63 "
        "--mdla-set-conv-xy-split-ic-threshold 99999 "
        "--gno LTS,Inception "
        "--gno-exp "
        "--gno-non-4d-tiling";

    int ret = s->fnCompilationCreateV2(s->model, COMPILATION_TYPE_NORMAL,
                                        OPT_FLAGS, &s->compilation);
    if (ret != NEURON_NO_ERROR || !s->compilation) {
        LOGE("NeuronCompilation_createV2 failed: %d", ret);
        return false;
    }

    s->fnCompilationSetOptString(s->compilation, OPT_FLAGS);

    ret = s->fnCompilationFinish(s->compilation);
    if (ret != NEURON_NO_ERROR) {
        LOGE("NeuronCompilation_finish failed: %d", ret);
        s->fnCompilationFree(s->compilation);
        s->compilation = nullptr;
        return false;
    }

    LOGI("Model compiled on NPU with MDLA flags");
    return true;
}

// ── Cache compiled network ────────────────────────────────────────────────
static bool saveCache(NeuronSession* s, const std::string& cache_dir) {
    if (!ensureDir(cache_dir)) { LOGE("Cannot create: %s", cache_dir.c_str()); return false; }

    size_t net_size = 0;
    int ret = s->fnCompilationGetSize(s->compilation, &net_size);
    if (ret != NEURON_NO_ERROR || net_size == 0) {
        LOGE("getCompiledNetworkSize failed: %d", ret); return false;
    }

    std::vector<uint8_t> buffer(net_size);
    ret = s->fnCompilationStore(s->compilation, buffer.data(), net_size);
    if (ret != NEURON_NO_ERROR) { LOGE("storeCompiledNetwork failed: %d", ret); return false; }

    std::string cache_path = cache_dir + "/" + baseName(s->model_path) + ".compiled";
    FILE* f = fopen(cache_path.c_str(), "wb");
    if (!f) { LOGE("Cannot write: %s", cache_path.c_str()); return false; }
    fwrite(buffer.data(), 1, net_size, f);
    fclose(f);

    LOGI("Cached compiled network: %s (%zu bytes)", cache_path.c_str(), net_size);
    return true;
}

// ── Restore from compiled cache ───────────────────────────────────────────
static bool loadFromCache(NeuronSession* s, const std::string& cache_dir) {
    std::string cache_path = cache_dir + "/" + baseName(s->model_path) + ".compiled";
    FILE* f = fopen(cache_path.c_str(), "rb");
    if (!f) return false;

    fseek(f, 0, SEEK_END);
    long size = ftell(f);
    fseek(f, 0, SEEK_SET);
    std::vector<uint8_t> buffer(size);
    fread(buffer.data(), 1, size, f);
    fclose(f);

    NeuronModel* m = nullptr;
    NeuronCompilation* c = nullptr;
    int ret = s->fnModelRestore(&m, &c, buffer.data(), buffer.size());
    if (ret != NEURON_NO_ERROR) {
        LOGE("restoreFromCompiledNetwork failed: %d", ret);
        if (m) s->fnModelFree(m);
        return false;
    }

    s->model = m;
    s->compilation = c;
    s->from_cache = true;
    LOGI("Restored from cache: %s", cache_path.c_str());
    return true;
}

// ── Create execution instance ──────────────────────────────────────────────
static bool createExecution(NeuronSession* s) {
    int ret = s->fnExecutionCreate(s->compilation, &s->execution);
    if (ret != NEURON_NO_ERROR || !s->execution) {
        LOGE("NeuronExecution_create failed: %d", ret);
        return false;
    }
    return true;
}

// ── Simple UTF-8 to codepoint tokenizer ───────────────────────────────────
static std::vector<int32_t> tokenize(const std::string& text) {
    std::vector<int32_t> tokens;
    for (size_t i = 0; i < text.size(); ) {
        unsigned char c = (unsigned char)text[i];
        if (c < 0x80) { tokens.push_back((int32_t)c); i++; }
        else if ((c & 0xE0) == 0xC0 && i+1 < text.size()) {
            tokens.push_back((((c & 0x1F) << 6) | ((unsigned char)text[i+1] & 0x3F)) + 0x100);
            i += 2;
        } else if ((c & 0xF0) == 0xE0 && i+2 < text.size()) {
            tokens.push_back((((c & 0x0F) << 12) | (((unsigned char)text[i+1] & 0x3F) << 6)
                | ((unsigned char)text[i+2] & 0x3F)) + 0x1000);
            i += 3;
        } else if ((c & 0xF8) == 0xF0 && i+3 < text.size()) {
            tokens.push_back((((c & 0x07) << 18) | (((unsigned char)text[i+1] & 0x3F) << 12)
                | (((unsigned char)text[i+2] & 0x3F) << 6)
                | ((unsigned char)text[i+3] & 0x3F)) + 0x10000);
            i += 4;
        } else { tokens.push_back((int32_t)c); i++; }
    }
    return tokens;
}

// ── Codepoint to UTF-8 detokenizer ────────────────────────────────────────
static std::string detokenize(const std::vector<int32_t>& tokens) {
    std::string result;
    for (int32_t tid : tokens) {
        if (tid == 0) break;
        if (tid >= 0 && tid < 0x80) { result += (char)tid; }
        else if (tid >= 0x100 && tid < 0x1000) {
            int32_t cp = tid - 0x100;
            result += (char)(0xC0 | (cp >> 6));
            result += (char)(0x80 | (cp & 0x3F));
        } else if (tid >= 0x1000 && tid < 0x110000) {
            int32_t cp = tid - 0x1000;
            result += (char)(0xE0 | (cp >> 12));
            result += (char)(0x80 | ((cp >> 6) & 0x3F));
            result += (char)(0x80 | (cp & 0x3F));
        } else { result += '?'; }
    }
    return result;
}

// ══════════════════════════════════════════════════════════════════════════
// JNI Methods
// =========================================================================

extern "C" JNIEXPORT jlong JNICALL
Java_com_aetheria_vance_brain_NeuronBridge_nativeInit(
    JNIEnv* env, jobject /*thiz*/,
    jstring modelPath, jstring cacheDir)
{
    try {
        const char* path = env->GetStringUTFChars(modelPath, nullptr);
        const char* cache = env->GetStringUTFChars(cacheDir, nullptr);
        std::string model_path(path);
        std::string cache_dir(cache);
        env->ReleaseStringUTFChars(modelPath, path);
        env->ReleaseStringUTFChars(cacheDir, cache);

        // ── Validate model file is a real TFLite flatbuffer ───────────────
        {
            FILE* vf = fopen(model_path.c_str(), "rb");
            if (!vf) { LOGE("Cannot open model file: %s", model_path.c_str()); return 0L; }
            uint8_t magic[8] = {0};
            size_t nrd = fread(magic, 1, 8, vf);
            fclose(vf);
            if (nrd < 8) { LOGE("Model file too small: %s", model_path.c_str()); return 0L; }
            // TFLite flatbuffer: "TFL3" at offset 0, or at offset 4 (MediaPipe bundle prefix)
            bool isTflite = (magic[0]=='T' && magic[1]=='F' && magic[2]=='L' && magic[3]=='3')
                         || (magic[4]=='T' && magic[5]=='F' && magic[6]=='L' && magic[7]=='3');
            if (!isTflite) {
                LOGE("Model file is not a valid TFLite flatbuffer — skipping NPU: %s", model_path.c_str());
                return 0L;
            }
        }

        std::lock_guard<std::mutex> lock(g_mutex);

        NeuronSession* session = new (std::nothrow) NeuronSession();
        if (!session) { LOGE("OOM"); return 0L; }
        session->model_path = model_path;

        // 1. Load adapter
        session->adapter_handle = loadAdapter();
        if (!session->adapter_handle) { delete session; return 0L; }

        // 2. Resolve symbols
        if (!resolveAll(session)) { delete session; return 0L; }

        // 3. Null-check every required function pointer ─────────────────────
        if (!session->fnModelCreate       || !session->fnModelFinish    ||
            !session->fnModelAddOperand   || !session->fnModelSetOperandValue ||
            !session->fnModelIdentifyInputsAndOutputs ||
            !session->fnModelRelaxComputationFloat32toFloat16 ||
            !session->fnCompilationCreateV2 || !session->fnCompilationFinish ||
            !session->fnCompilationFree   || !session->fnCompilationGetSize ||
            !session->fnCompilationStore  || !session->fnCompilationSetOptString ||
            !session->fnExecutionCreate   || !session->fnExecutionFree   ||
            !session->fnExecutionSetInput || !session->fnExecutionSetOutput ||
            !session->fnExecutionCompute) {
            LOGE("Missing required Neuron function pointers — NPU unavailable");
            delete session;
            return 0L;
        }

        // 4. Try cache
        std::string neuron_cache = cache_dir + std::string("/neuron_cache");
        if (loadFromCache(session, neuron_cache)) {
            if (createExecution(session)) {
                LOGI("Session from cache: %p", (void*)session);
                return (jlong)session;
            }
            // Cache was corrupt, clean up and rebuild
            LOGE("Cache rebuild needed");
            if (session->compilation) { session->fnCompilationFree(session->compilation); session->compilation = nullptr; }
            if (session->model) { session->fnModelFree(session->model); session->model = nullptr; }
        }

        // 5. Load model binary
        std::vector<uint8_t> model_data;
        if (!loadModelBinary(model_path, model_data)) { delete session; return 0L; }

        // 6. Build model
        if (!buildModel(session, model_data)) {
            if (session->model) { session->fnModelFree(session->model); session->model = nullptr; }
            delete session; return 0L;
        }

        // 7. Compile
        if (!compileModel(session)) {
            if (session->compilation) { session->fnCompilationFree(session->compilation); }
            if (session->model) { session->fnModelFree(session->model); }
            delete session; return 0L;
        }

        // 8. Save cache
        saveCache(session, neuron_cache);

        // 9. Create execution
        if (!createExecution(session)) {
            if (session->compilation) { session->fnCompilationFree(session->compilation); }
            if (session->model) { session->fnModelFree(session->model); }
            delete session; return 0L;
        }

        LOGI("Session fresh: %p", (void*)session);
        return (jlong)session;
    } catch (const std::exception& e) {
        LOGE("nativeInit exception: %s", e.what());
        return 0L;
    } catch (...) {
        LOGE("nativeInit unknown exception");
        return 0L;
    }
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_aetheria_vance_brain_NeuronBridge_nativeInfer(
    JNIEnv* env, jobject /*thiz*/,
    jlong handle, jstring prompt)
{
    const char* prompt_str = env->GetStringUTFChars(prompt, nullptr);
    std::string input(prompt_str);
    env->ReleaseStringUTFChars(prompt, prompt_str);

    NeuronSession* s = (NeuronSession*)handle;
    if (!s || !s->execution) {
        return env->NewStringUTF("NPU_ERROR: invalid session");
    }

    // Tokenize
    std::vector<int32_t> tokens = tokenize(input);
    if (tokens.empty()) {
        return env->NewStringUTF("NPU_ERROR: empty input");
    }
    if (tokens.size() > 512) tokens.resize(512);

    // Padded input buffer (512 int32)
    std::vector<int32_t> in_buf(512, 0);
    memcpy(in_buf.data(), tokens.data(), tokens.size() * sizeof(int32_t));

    // Set input
    uint32_t in_dims[] = {1, 512};
    NeuronOperandType in_type = {};
    in_type.type = NEURON_TENSOR_INT32;
    in_type.dimensionCount = 2;
    in_type.dimensions = in_dims;

    int ret = s->fnExecutionSetInput(s->execution, 0, &in_type,
                                      in_buf.data(), in_buf.size() * sizeof(int32_t));
    if (ret != NEURON_NO_ERROR) {
        LOGE("setInput failed: %d", ret);
        return env->NewStringUTF("NPU_ERROR: setInput");
    }

    // Prepare output
    std::vector<int32_t> out_buf(512, 0);
    uint32_t out_dims[] = {1, 512};
    NeuronOperandType out_type = {};
    out_type.type = NEURON_TENSOR_INT32;
    out_type.dimensionCount = 2;
    out_type.dimensions = out_dims;

    ret = s->fnExecutionSetOutput(s->execution, 0, &out_type,
                                   out_buf.data(), out_buf.size() * sizeof(int32_t));
    if (ret != NEURON_NO_ERROR) {
        LOGE("setOutput failed: %d", ret);
        return env->NewStringUTF("NPU_ERROR: setOutput");
    }

    // Compute
    ret = s->fnExecutionCompute(s->execution);
    if (ret != NEURON_NO_ERROR) {
        LOGE("compute failed: %d", ret);
        return env->NewStringUTF("NPU_ERROR: compute");
    }

    // Detokenize
    std::string result = detokenize(out_buf);
    return env->NewStringUTF(result.c_str());
}

extern "C" JNIEXPORT void JNICALL
Java_com_aetheria_vance_brain_NeuronBridge_nativeClose(
    JNIEnv* /*env*/, jobject /*thiz*/, jlong handle)
{
    NeuronSession* s = (NeuronSession*)handle;
    if (!s) return;
    std::lock_guard<std::mutex> lock(g_mutex);
    if (s->execution) s->fnExecutionFree(s->execution);
    if (s->compilation) s->fnCompilationFree(s->compilation);
    if (s->model) s->fnModelFree(s->model);
    if (s->adapter_handle) dlclose(s->adapter_handle);
    LOGI("Session closed: %p", (void*)s);
    delete s;
}
