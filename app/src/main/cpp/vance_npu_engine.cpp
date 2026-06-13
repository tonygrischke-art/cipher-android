// ============================================================================
// VanceNpuEngine — Direct TFLite C API + NNAPI Delegate
// File: app/src/main/cpp/vance_npu_engine.cpp
// Target: MT6878 (Dimensity 7300) via system NNAPI + MTK shim service
//
// Uses the TFLite C API loaded via dlsym from libtensorflowlite_jni.so.
// NNAPI delegate is created via TfLiteNnapiDelegateCreate with opaque options.
// Falls back to CPU if NNAPI delegate creation fails.
//
// JNI package: com.aetheria.vance.npu.VanceNpuJni
// ============================================================================

#include <jni.h>
#include <dlfcn.h>
#include <android/log.h>
#include <string>
#include <mutex>
#include <cstring>

#include "tensorflow/lite/c/c_api.h"
#include "tensorflow/lite/c/c_api_experimental.h"
#include "tensorflow/lite/c/common.h"
#include "tensorflow/lite/delegates/nnapi/nnapi_delegate_c_api.h"

#define LOG_TAG "VanceNpuEngine"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

// ── TFLite C API function pointers (loaded via dlsym) ────────────────────
static TfLiteModel* (*pfn_TfLiteModelCreateFromFile)(const char*) = nullptr;
static void (*pfn_TfLiteModelDelete)(TfLiteModel*) = nullptr;
static TfLiteInterpreterOptions* (*pfn_TfLiteInterpreterOptionsCreate)(void) = nullptr;
static void (*pfn_TfLiteInterpreterOptionsDelete)(TfLiteInterpreterOptions*) = nullptr;
static void (*pfn_TfLiteInterpreterOptionsSetNumThreads)(TfLiteInterpreterOptions*, int32_t) = nullptr;
static void (*pfn_TfLiteInterpreterOptionsAddDelegate)(TfLiteInterpreterOptions*, TfLiteDelegate*) = nullptr;
static TfLiteInterpreter* (*pfn_TfLiteInterpreterCreate)(TfLiteModel*, const TfLiteInterpreterOptions*) = nullptr;
static void (*pfn_TfLiteInterpreterDelete)(TfLiteInterpreter*) = nullptr;
static TfLiteStatus (*pfn_TfLiteInterpreterAllocateTensors)(TfLiteInterpreter*) = nullptr;
static TfLiteStatus (*pfn_TfLiteInterpreterInvoke)(TfLiteInterpreter*) = nullptr;
static int32_t (*pfn_TfLiteInterpreterGetInputTensorCount)(const TfLiteInterpreter*) = nullptr;
static int32_t (*pfn_TfLiteInterpreterGetOutputTensorCount)(const TfLiteInterpreter*) = nullptr;
static TfLiteTensor* (*pfn_TfLiteInterpreterGetInputTensor)(const TfLiteInterpreter*, int32_t) = nullptr;
static const TfLiteTensor* (*pfn_TfLiteInterpreterGetOutputTensor)(const TfLiteInterpreter*, int32_t) = nullptr;
static TfLiteType (*pfn_TfLiteTensorType)(const TfLiteTensor*) = nullptr;
static int32_t (*pfn_TfLiteTensorNumDims)(const TfLiteTensor*) = nullptr;
static int32_t (*pfn_TfLiteTensorDim)(const TfLiteTensor*, int32_t) = nullptr;
static size_t (*pfn_TfLiteTensorByteSize)(const TfLiteTensor*) = nullptr;
static void* (*pfn_TfLiteTensorData)(const TfLiteTensor*) = nullptr;
static const char* (*pfn_TfLiteTensorName)(const TfLiteTensor*) = nullptr;
static TfLiteDelegate* (*pfn_TfLiteNnapiDelegateCreate)(const TfLiteNnapiDelegateOptions*) = nullptr;
static void (*pfn_TfLiteNnapiDelegateDelete)(TfLiteDelegate*) = nullptr;
static void (*pfn_TfLiteNnapiDelegateOptionsDefault)(TfLiteNnapiDelegateOptions*) = nullptr;

// ── Engine state ──────────────────────────────────────────────────────────
struct EngineState {
    TfLiteModel*              model       = nullptr;
    TfLiteInterpreterOptions* options     = nullptr;
    TfLiteInterpreter*        interpreter = nullptr;
    TfLiteDelegate*           delegate    = nullptr;
};

static EngineState g_engine;
static std::mutex  g_mutex;
static bool        g_tflite_resolved = false;

// ── Resolve TFLite C API symbols via dlsym ───────────────────────────────
static bool resolve_tflite_symbols() {
    if (g_tflite_resolved) return true;

    void* handles[] = {
        dlopen("libtensorflowlite_jni.so", RTLD_NOW | RTLD_NOLOAD),
        dlopen("libneuronusdk_adapter.mtk.so", RTLD_NOW | RTLD_NOLOAD),
        RTLD_DEFAULT,
        nullptr
    };

    void* h = nullptr;
    for (int i = 0; handles[i] != nullptr; i++) {
        void* test = dlsym(handles[i], "TfLiteModelCreateFromFile");
        if (test) { h = handles[i]; break; }
    }

    if (!h) {
        h = dlopen("libtensorflowlite_jni.so", RTLD_NOW);
        if (!h) h = dlopen("libtensorflowlite.so", RTLD_NOW);
        if (!h) h = RTLD_DEFAULT;
    }

    if (!h) { LOGE("No TFLite library found"); return false; }

#define LOAD_SYM(name) do { \
    pfn_##name = reinterpret_cast<decltype(pfn_##name)>(dlsym(h, #name)); \
    if (!pfn_##name) { LOGE("dlsym failed: %s", #name); return false; } \
} while(0)

    LOAD_SYM(TfLiteModelCreateFromFile);
    LOAD_SYM(TfLiteModelDelete);
    LOAD_SYM(TfLiteInterpreterOptionsCreate);
    LOAD_SYM(TfLiteInterpreterOptionsDelete);
    LOAD_SYM(TfLiteInterpreterOptionsSetNumThreads);
    LOAD_SYM(TfLiteInterpreterOptionsAddDelegate);
    LOAD_SYM(TfLiteInterpreterCreate);
    LOAD_SYM(TfLiteInterpreterDelete);
    LOAD_SYM(TfLiteInterpreterAllocateTensors);
    LOAD_SYM(TfLiteInterpreterInvoke);
    LOAD_SYM(TfLiteInterpreterGetInputTensorCount);
    LOAD_SYM(TfLiteInterpreterGetOutputTensorCount);
    LOAD_SYM(TfLiteInterpreterGetInputTensor);
    LOAD_SYM(TfLiteInterpreterGetOutputTensor);
    LOAD_SYM(TfLiteTensorType);
    LOAD_SYM(TfLiteTensorNumDims);
    LOAD_SYM(TfLiteTensorDim);
    LOAD_SYM(TfLiteTensorByteSize);
    LOAD_SYM(TfLiteTensorData);
    LOAD_SYM(TfLiteTensorName);
    LOAD_SYM(TfLiteNnapiDelegateCreate);
    LOAD_SYM(TfLiteNnapiDelegateDelete);
    LOAD_SYM(TfLiteNnapiDelegateOptionsDefault);

#undef LOAD_SYM

    g_tflite_resolved = true;
    LOGI("TFLite C API symbols resolved OK");
    return true;
}

// ── Log tensor info for debugging ─────────────────────────────────────────
static void log_tensor_info(const char* label, const TfLiteTensor* tensor) {
    if (!tensor) { LOGI("%s: null", label); return; }
    const char* name = pfn_TfLiteTensorName ? pfn_TfLiteTensorName(tensor) : "?";
    int32_t dims = pfn_TfLiteTensorNumDims(tensor);
    std::string shape = "[";
    for (int i = 0; i < dims; i++) {
        if (i > 0) shape += ",";
        shape += std::to_string(pfn_TfLiteTensorDim(tensor, i));
    }
    shape += "]";
    LOGI("%s: name=%s type=%d dims=%s bytes=%zu", label, name,
         pfn_TfLiteTensorType(tensor), shape.c_str(),
         pfn_TfLiteTensorByteSize(tensor));
}

// ── JNI: initializeModel ──────────────────────────────────────────────────
extern "C" JNIEXPORT jboolean JNICALL
Java_com_aetheria_vance_npu_VanceNpuJni_initializeModel(
        JNIEnv* env, jobject thiz, jstring model_path) {

    std::lock_guard<std::mutex> lock(g_mutex);

    if (!model_path) { LOGE("model_path null"); return JNI_FALSE; }
    const char* path = env->GetStringUTFChars(model_path, nullptr);
    LOGI("Loading model: %s", path);

    if (!resolve_tflite_symbols()) {
        LOGE("Failed to resolve TFLite symbols");
        env->ReleaseStringUTFChars(model_path, path);
        return JNI_FALSE;
    }

    // Clean up any previous instance
    if (g_engine.interpreter) {
        pfn_TfLiteInterpreterDelete(g_engine.interpreter);
        g_engine.interpreter = nullptr;
    }
    if (g_engine.delegate) {
        pfn_TfLiteNnapiDelegateDelete(g_engine.delegate);
        g_engine.delegate = nullptr;
    }
    if (g_engine.options) {
        pfn_TfLiteInterpreterOptionsDelete(g_engine.options);
        g_engine.options = nullptr;
    }
    if (g_engine.model) {
        pfn_TfLiteModelDelete(g_engine.model);
        g_engine.model = nullptr;
    }

    // Load model
    g_engine.model = pfn_TfLiteModelCreateFromFile(path);
    env->ReleaseStringUTFChars(model_path, path);
    if (!g_engine.model) { LOGE("TfLiteModelCreateFromFile failed"); return JNI_FALSE; }
    LOGI("Model loaded OK");

    // Create interpreter options
    g_engine.options = pfn_TfLiteInterpreterOptionsCreate();
    if (!g_engine.options) { LOGE("TfLiteInterpreterOptionsCreate failed"); return JNI_FALSE; }
    pfn_TfLiteInterpreterOptionsSetNumThreads(g_engine.options, 4);

    // Try NNAPI delegate
    if (pfn_TfLiteNnapiDelegateOptionsDefault && pfn_TfLiteNnapiDelegateCreate) {
        TfLiteNnapiDelegateOptions nnapi_opts;
        memset(&nnapi_opts, 0, sizeof(nnapi_opts));
        pfn_TfLiteNnapiDelegateOptionsDefault(&nnapi_opts);

        g_engine.delegate = pfn_TfLiteNnapiDelegateCreate(&nnapi_opts);
        if (g_engine.delegate) {
            pfn_TfLiteInterpreterOptionsAddDelegate(g_engine.options, g_engine.delegate);
            LOGI("NNAPI delegate active");
        } else {
            LOGI("NNAPI delegate creation failed — CPU TFLite fallback");
        }
    } else {
        LOGI("NNAPI delegate symbols not available — CPU TFLite fallback");
    }

    // Create interpreter
    g_engine.interpreter = pfn_TfLiteInterpreterCreate(g_engine.model, g_engine.options);
    if (!g_engine.interpreter) {
        LOGE("TfLiteInterpreterCreate failed");
        if (g_engine.delegate) { pfn_TfLiteNnapiDelegateDelete(g_engine.delegate); g_engine.delegate = nullptr; }
        pfn_TfLiteInterpreterOptionsDelete(g_engine.options); g_engine.options = nullptr;
        pfn_TfLiteModelDelete(g_engine.model); g_engine.model = nullptr;
        return JNI_FALSE;
    }

    // Allocate tensors
    if (pfn_TfLiteInterpreterAllocateTensors(g_engine.interpreter) != kTfLiteOk) {
        LOGE("AllocateTensors failed");
        pfn_TfLiteInterpreterDelete(g_engine.interpreter); g_engine.interpreter = nullptr;
        if (g_engine.delegate) { pfn_TfLiteNnapiDelegateDelete(g_engine.delegate); g_engine.delegate = nullptr; }
        pfn_TfLiteInterpreterOptionsDelete(g_engine.options); g_engine.options = nullptr;
        pfn_TfLiteModelDelete(g_engine.model); g_engine.model = nullptr;
        return JNI_FALSE;
    }

    // Log tensor info
    int32_t in_count = pfn_TfLiteInterpreterGetInputTensorCount(g_engine.interpreter);
    int32_t out_count = pfn_TfLiteInterpreterGetOutputTensorCount(g_engine.interpreter);
    LOGI("Interpreter ready: %d inputs, %d outputs", in_count, out_count);
    for (int i = 0; i < in_count; i++) {
        std::string label = "  input[" + std::to_string(i) + "]";
        log_tensor_info(label.c_str(), pfn_TfLiteInterpreterGetInputTensor(g_engine.interpreter, i));
    }
    for (int i = 0; i < out_count; i++) {
        std::string label = "  output[" + std::to_string(i) + "]";
        log_tensor_info(label.c_str(), pfn_TfLiteInterpreterGetOutputTensor(g_engine.interpreter, i));
    }

    LOGI("VanceNpuEngine initialized successfully");
    return JNI_TRUE;
}

// ── JNI: runInference ─────────────────────────────────────────────────────
extern "C" JNIEXPORT jstring JNICALL
Java_com_aetheria_vance_npu_VanceNpuJni_runInference(
        JNIEnv* env, jobject thiz, jstring prompt) {

    std::lock_guard<std::mutex> lock(g_mutex);

    if (!g_engine.interpreter) {
        LOGE("runInference called before init");
        return env->NewStringUTF("[ERROR: not initialized]");
    }

    if (!prompt) {
        LOGE("prompt null");
        return env->NewStringUTF("[ERROR: null prompt]");
    }

    // Copy prompt into input tensor 0
    const char* prompt_str = env->GetStringUTFChars(prompt, nullptr);
    TfLiteTensor* input = pfn_TfLiteInterpreterGetInputTensor(g_engine.interpreter, 0);
    if (input && pfn_TfLiteTensorData(input)) {
        size_t max_len = pfn_TfLiteTensorByteSize(input);
        size_t copy_len = strlen(prompt_str);
        if (copy_len >= max_len) copy_len = max_len - 1;
        memcpy(pfn_TfLiteTensorData(input), prompt_str, copy_len);
        ((char*)pfn_TfLiteTensorData(input))[copy_len] = '\0';
    }
    env->ReleaseStringUTFChars(prompt, prompt_str);

    // Run inference
    if (pfn_TfLiteInterpreterInvoke(g_engine.interpreter) != kTfLiteOk) {
        LOGE("Invoke failed");
        return env->NewStringUTF("[ERROR: invoke failed]");
    }

    // Read output tensor 0
    const TfLiteTensor* output = pfn_TfLiteInterpreterGetOutputTensor(g_engine.interpreter, 0);
    if (output && pfn_TfLiteTensorData(output)) {
        size_t out_bytes = pfn_TfLiteTensorByteSize(output);
        // Treat output as string (null-terminated or fixed-length)
        const char* out_str = static_cast<const char*>(pfn_TfLiteTensorData(output));
        // Safety: ensure null termination
        size_t len = strnlen(out_str, out_bytes);
        std::string result(out_str, len);
        LOGI("Inference output length: %zu bytes", len);
        return env->NewStringUTF(result.c_str());
    }

    return env->NewStringUTF("[ERROR: no output tensor]");
}

// ── JNI: terminateEngine ──────────────────────────────────────────────────
extern "C" JNIEXPORT void JNICALL
Java_com_aetheria_vance_npu_VanceNpuJni_terminateEngine(JNIEnv* env, jobject thiz) {
    std::lock_guard<std::mutex> lock(g_mutex);
    LOGI("Tearing down VanceNpuEngine");
    if (g_engine.interpreter) { pfn_TfLiteInterpreterDelete(g_engine.interpreter); g_engine.interpreter = nullptr; }
    if (g_engine.delegate)    { pfn_TfLiteNnapiDelegateDelete(g_engine.delegate);     g_engine.delegate    = nullptr; }
    if (g_engine.options)     { pfn_TfLiteInterpreterOptionsDelete(g_engine.options);  g_engine.options     = nullptr; }
    if (g_engine.model)       { pfn_TfLiteModelDelete(g_engine.model);                g_engine.model       = nullptr; }
    LOGI("VanceNpuEngine torn down");
}
