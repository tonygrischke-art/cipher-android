// ============================================================================
// WakeNPUEngine — TFLite Wake Word Classifier via NNAPI Delegate
// File: app/src/main/cpp/wake_npu_engine.cpp
// Target: MT6878 (Dimensity 7300) NPU 655 via mtk-neuron_shim
//
// Separate from libneuron_bridge.so (LLM path). This library handles only
// the wake word classifier TFLite model with INT8 quantization.
//
// Chain: TFLite → NnApiDelegate → System NNAPI APEX → MTK SL driver → NPU
// ============================================================================

#include <jni.h>
#include <dlfcn.h>
#include <android/log.h>
#include <cstring>
#include <cstdlib>

#include "tflite_stubs.h"

#define LOG_TAG "WakeNPUEngine"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

// ── TFLite function pointers (resolved via dlsym) ──────────────────────────
static TfLiteModel* (*pfn_TfLiteModelCreateFromFile)(const char*) = nullptr;
static void (*pfn_TfLiteModelDelete)(TfLiteModel*) = nullptr;
static TfLiteInterpreterOptions* (*pfn_TfLiteInterpreterOptionsCreate)(void) = nullptr;
static void (*pfn_TfLiteInterpreterOptionsDelete)(TfLiteInterpreterOptions*) = nullptr;
static void (*pfn_TfLiteInterpreterOptionsSetNumThreads)(TfLiteInterpreterOptions*, int) = nullptr;
static void (*pfn_TfLiteInterpreterOptionsAddDelegate)(TfLiteInterpreterOptions*, TfLiteDelegate*) = nullptr;
static TfLiteInterpreter* (*pfn_TfLiteInterpreterCreate)(TfLiteModel*, const TfLiteInterpreterOptions*) = nullptr;
static void (*pfn_TfLiteInterpreterDelete)(TfLiteInterpreter*) = nullptr;
static TfLiteStatus (*pfn_TfLiteInterpreterAllocateTensors)(TfLiteInterpreter*) = nullptr;
static TfLiteTensor* (*pfn_TfLiteInterpreterGetInputTensor)(const TfLiteInterpreter*, int32_t) = nullptr;
static const TfLiteTensor* (*pfn_TfLiteInterpreterGetOutputTensor)(const TfLiteInterpreter*, int32_t) = nullptr;
static TfLiteStatus (*pfn_TfLiteInterpreterInvoke)(TfLiteInterpreter*) = nullptr;
static void* (*pfn_TfLiteTensorData)(const TfLiteTensor*) = nullptr;
static size_t (*pfn_TfLiteTensorByteSize)(const TfLiteTensor*) = nullptr;

// NNAPI delegate function pointers
static TfLiteDelegate* (*pfn_TfLiteNnapiDelegateCreate)(const TfLiteNnapiDelegateOptions*) = nullptr;
static void (*pfn_TfLiteNnapiDelegateDelete)(TfLiteDelegate*) = nullptr;

static bool g_tflite_resolved = false;

// ── Resolve TFLite symbols from loaded libraries ───────────────────────────
static bool resolve_tflite_symbols() {
    if (g_tflite_resolved) return true;

    // Try libtensorflowlite_jni.so first (from TFLite AAR), then RTLD_DEFAULT
    void* handles[] = {
        dlopen("libtensorflowlite_jni.so", RTLD_LAZY | RTLD_NOLOAD),
        nullptr  // sentinel — will try RTLD_DEFAULT next
    };

    void* h = nullptr;
    const char* h_name = nullptr;

    for (int i = 0; i < 2; i++) {
        void* test_handle;
        if (i == 0) {
            test_handle = handles[0];
        } else {
            test_handle = RTLD_DEFAULT;
        }
        if (!test_handle) continue;

        void* test = dlsym(test_handle, "TfLiteModelCreateFromFile");
        if (test) {
            h = test_handle;
            h_name = (i == 0) ? "libtensorflowlite_jni.so" : "RTLD_DEFAULT";
            break;
        }
    }

    if (!h) {
        LOGE("resolve_tflite_symbols: no TFLite library found");
        return false;
    }

    LOGI("WakeNPUEngine TFLite resolved from: %s (handle=%p)", h_name, h);

    auto R = [&](const char* sym, auto& fn) -> bool {
        void* p = dlsym(h, sym);
        if (p) {
            fn = reinterpret_cast<decltype(fn)>(p);
            return true;
        } else {
            LOGE("  dlsym FAILED: %s — %s", sym, dlerror());
            return false;
        }
    };

    bool ok = true;
    ok &= R("TfLiteModelCreateFromFile",              pfn_TfLiteModelCreateFromFile);
    ok &= R("TfLiteModelDelete",                      pfn_TfLiteModelDelete);
    ok &= R("TfLiteInterpreterOptionsCreate",         pfn_TfLiteInterpreterOptionsCreate);
    ok &= R("TfLiteInterpreterOptionsDelete",         pfn_TfLiteInterpreterOptionsDelete);
    ok &= R("TfLiteInterpreterOptionsSetNumThreads",  pfn_TfLiteInterpreterOptionsSetNumThreads);
    ok &= R("TfLiteInterpreterOptionsAddDelegate",    pfn_TfLiteInterpreterOptionsAddDelegate);
    ok &= R("TfLiteInterpreterCreate",                pfn_TfLiteInterpreterCreate);
    ok &= R("TfLiteInterpreterDelete",                pfn_TfLiteInterpreterDelete);
    ok &= R("TfLiteInterpreterAllocateTensors",       pfn_TfLiteInterpreterAllocateTensors);
    ok &= R("TfLiteInterpreterGetInputTensor",        pfn_TfLiteInterpreterGetInputTensor);
    ok &= R("TfLiteInterpreterGetOutputTensor",       pfn_TfLiteInterpreterGetOutputTensor);
    ok &= R("TfLiteInterpreterInvoke",                pfn_TfLiteInterpreterInvoke);
    ok &= R("TfLiteTensorData",                       pfn_TfLiteTensorData);
    ok &= R("TfLiteTensorByteSize",                   pfn_TfLiteTensorByteSize);
    ok &= R("TfLiteNnapiDelegateCreate",              pfn_TfLiteNnapiDelegateCreate);
    ok &= R("TfLiteNnapiDelegateDelete",              pfn_TfLiteNnapiDelegateDelete);

    if (ok) {
        LOGI("WakeNPUEngine: All TFLite C API symbols resolved");
        g_tflite_resolved = true;
    } else {
        LOGE("WakeNPUEngine: Some symbols failed to resolve");
    }
    return ok;
}

// ── Global state ────────────────────────────────────────────────────────────
static TfLiteModel*                g_model       = nullptr;
static TfLiteInterpreterOptions*   g_options     = nullptr;
static TfLiteInterpreter*          g_interpreter = nullptr;
static TfLiteDelegate*             g_delegate    = nullptr;

// ── JNI: Initialize model ───────────────────────────────────────────────────
extern "C" JNIEXPORT jboolean JNICALL
Java_com_aetheria_vance_wake_WakeNPUEngine_initializeModel(
    JNIEnv* env, jobject thiz, jstring model_path) {

    if (model_path == nullptr) {
        LOGE("model_path is null");
        return JNI_FALSE;
    }

    if (!resolve_tflite_symbols()) {
        LOGE("Failed to resolve TFLite symbols");
        return JNI_FALSE;
    }

    const char* path = env->GetStringUTFChars(model_path, nullptr);
    LOGI("Loading wake word model: %s", path);

    g_model = pfn_TfLiteModelCreateFromFile(path);
    env->ReleaseStringUTFChars(model_path, path);
    if (!g_model) {
        LOGE("TfLiteModelCreateFromFile failed");
        return JNI_FALSE;
    }

    g_options = pfn_TfLiteInterpreterOptionsCreate();
    if (!g_options) {
        LOGE("TfLiteInterpreterOptionsCreate failed");
        pfn_TfLiteModelDelete(g_model);
        g_model = nullptr;
        return JNI_FALSE;
    }
    pfn_TfLiteInterpreterOptionsSetNumThreads(g_options, 2);

    // Create NNAPI delegate with zero-initialized options.
    // Skip TfLiteNnapiDelegateOptionsDefault — crashes on MT6878 TFLite build.
    // Zero-init is sufficient: all fields = 0 = default behavior.
    if (pfn_TfLiteNnapiDelegateCreate) {
        TfLiteNnapiDelegateOptions nn_opts;
        memset(&nn_opts, 0, sizeof(nn_opts));

        g_delegate = pfn_TfLiteNnapiDelegateCreate(&nn_opts);
        if (g_delegate) {
            pfn_TfLiteInterpreterOptionsAddDelegate(g_options, g_delegate);
            LOGI("NNAPI delegate attached (mtk-neuron_shim via system NNAPI)");
        } else {
            LOGE("NNAPI delegate creation failed — check vendor HAL availability");
            // Continue without delegate — will use CPU fallback
        }
    } else {
        LOGE("TfLiteNnapiDelegateCreate not available — CPU only");
    }

    g_interpreter = pfn_TfLiteInterpreterCreate(g_model, g_options);
    if (!g_interpreter) {
        LOGE("TfLiteInterpreterCreate failed");
        if (g_delegate) { pfn_TfLiteNnapiDelegateDelete(g_delegate); g_delegate = nullptr; }
        pfn_TfLiteInterpreterOptionsDelete(g_options); g_options = nullptr;
        pfn_TfLiteModelDelete(g_model); g_model = nullptr;
        return JNI_FALSE;
    }

    if (pfn_TfLiteInterpreterAllocateTensors(g_interpreter) != kTfLiteOk) {
        LOGE("TfLiteInterpreterAllocateTensors failed");
        pfn_TfLiteInterpreterDelete(g_interpreter); g_interpreter = nullptr;
        if (g_delegate) { pfn_TfLiteNnapiDelegateDelete(g_delegate); g_delegate = nullptr; }
        pfn_TfLiteInterpreterOptionsDelete(g_options); g_options = nullptr;
        pfn_TfLiteModelDelete(g_model); g_model = nullptr;
        return JNI_FALSE;
    }

    LOGI("WakeNPUEngine ready. Tensors allocated.");
    return JNI_TRUE;
}

// ── JNI: Run inference ──────────────────────────────────────────────────────
extern "C" JNIEXPORT jfloat JNICALL
Java_com_aetheria_vance_wake_WakeNPUEngine_runInference(
    JNIEnv* env, jobject thiz, jfloatArray mfcc_input) {

    if (!g_interpreter) {
        LOGE("runInference called before initializeModel");
        return -1.0f;
    }

    jsize input_len = env->GetArrayLength(mfcc_input);
    jfloat* input_data = env->GetFloatArrayElements(mfcc_input, nullptr);

    TfLiteTensor* input_tensor = pfn_TfLiteInterpreterGetInputTensor(g_interpreter, 0);
    if (!input_tensor) {
        LOGE("Failed to get input tensor");
        env->ReleaseFloatArrayElements(mfcc_input, input_data, JNI_ABORT);
        return -1.0f;
    }

    size_t tensor_size = pfn_TfLiteTensorByteSize(input_tensor);
    size_t input_size = input_len * sizeof(float);
    if (input_size > tensor_size) {
        LOGE("Input size mismatch: input=%zu tensor=%zu", input_size, tensor_size);
        env->ReleaseFloatArrayElements(mfcc_input, input_data, JNI_ABORT);
        return -1.0f;
    }

    memcpy(pfn_TfLiteTensorData(input_tensor), input_data, input_size);
    env->ReleaseFloatArrayElements(mfcc_input, input_data, JNI_ABORT);

    if (pfn_TfLiteInterpreterInvoke(g_interpreter) != kTfLiteOk) {
        LOGE("TfLiteInterpreterInvoke failed");
        return -1.0f;
    }

    const TfLiteTensor* output_tensor = pfn_TfLiteInterpreterGetOutputTensor(g_interpreter, 0);
    if (!output_tensor) {
        LOGE("Failed to get output tensor");
        return -1.0f;
    }

    float score = ((float*)pfn_TfLiteTensorData(output_tensor))[0];
    LOGI("Wake word score: %.4f", score);
    return score;
}

// ── JNI: Cleanup ────────────────────────────────────────────────────────────
extern "C" JNIEXPORT void JNICALL
Java_com_aetheria_vance_wake_WakeNPUEngine_terminateEngine(
    JNIEnv* env, jobject thiz) {

    LOGI("Tearing down WakeNPUEngine");
    if (g_interpreter) {
        pfn_TfLiteInterpreterDelete(g_interpreter);
        g_interpreter = nullptr;
    }
    if (g_delegate) {
        pfn_TfLiteNnapiDelegateDelete(g_delegate);
        g_delegate = nullptr;
    }
    if (g_options) {
        pfn_TfLiteInterpreterOptionsDelete(g_options);
        g_options = nullptr;
    }
    if (g_model) {
        pfn_TfLiteModelDelete(g_model);
        g_model = nullptr;
    }
}
