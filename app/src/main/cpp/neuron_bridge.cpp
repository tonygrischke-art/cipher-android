// ============================================================================
// NeuronBridge — Standard NNAPI Delegate Path
// File: app/src/main/cpp/neuron_bridge.cpp
// Target: MT6878 (Dimensity 7300) via system NNAPI + MTK shim service
//
// Uses the standard TfLiteNnapiDelegateCreate() path instead of the
// NnApiSL shim injection. The system NNAPI (APEX) routes to the MTK
// vendor driver (libneuralnetworks_sl_driver_mtk_prebuilt.so) which
// talks to the NeuronApusys HAL.
//
// Chain: TFLite → NnApiDelegate → System NNAPI APEX → MTK SL driver → NPU
// ============================================================================

#include <jni.h>
#include <dlfcn.h>
#include <android/log.h>
#include <string>
#include <mutex>
#include <cstring>

#include "tflite_headers/c_api.h"
#include "tflite_headers/c_api_experimental.h"
#include "tflite_headers/common.h"
#include "tflite_headers/nnapi_delegate_c_api.h"

#define LOG_TAG "NeuronBridge"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define LOGW(...) __android_log_print(ANDROID_LOG_WARN, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

// ── TFLite C API function pointers (loaded via dlsym) ────────────────────
static TfLiteModel* (*pfn_TfLiteModelCreateFromFile)(const char*) = nullptr;
static void (*pfn_TfLiteModelDelete)(TfLiteModel*) = nullptr;
static TfLiteInterpreterOptions* (*pfn_TfLiteInterpreterOptionsCreate)(void) = nullptr;
static void (*pfn_TfLiteInterpreterOptionsDelete)(TfLiteInterpreterOptions*) = nullptr;
static void (*pfn_TfLiteInterpreterOptionsAddDelegate)(TfLiteInterpreterOptions*, TfLiteDelegate*) = nullptr;
static TfLiteInterpreter* (*pfn_TfLiteInterpreterCreate)(TfLiteModel*, const TfLiteInterpreterOptions*) = nullptr;
static void (*pfn_TfLiteInterpreterDelete)(TfLiteInterpreter*) = nullptr;
static TfLiteStatus (*pfn_TfLiteInterpreterAllocateTensors)(TfLiteInterpreter*) = nullptr;
static int32_t (*pfn_TfLiteInterpreterGetInputTensorCount)(const TfLiteInterpreter*) = nullptr;
static int32_t (*pfn_TfLiteInterpreterGetOutputTensorCount)(const TfLiteInterpreter*) = nullptr;
static TfLiteTensor* (*pfn_TfLiteInterpreterGetInputTensor)(const TfLiteInterpreter*, int32_t) = nullptr;
static const TfLiteTensor* (*pfn_TfLiteInterpreterGetOutputTensor)(const TfLiteInterpreter*, int32_t) = nullptr;
static TfLiteStatus (*pfn_TfLiteInterpreterInvoke)(TfLiteInterpreter*) = nullptr;
static TfLiteType (*pfn_TfLiteTensorType)(const TfLiteTensor*) = nullptr;
static size_t (*pfn_TfLiteTensorByteSize)(const TfLiteTensor*) = nullptr;
static void* (*pfn_TfLiteTensorData)(const TfLiteTensor*) = nullptr;
static TfLiteDelegate* (*pfn_TfLiteNnapiDelegateCreate)(const TfLiteNnapiDelegateOptions*) = nullptr;
static void (*pfn_TfLiteNnapiDelegateDelete)(TfLiteDelegate*) = nullptr;
static void (*pfn_TfLiteNnapiDelegateOptionsDefault)(TfLiteNnapiDelegateOptions*) = nullptr;

// ── Session state ─────────────────────────────────────────────────────────
struct NpuSession {
    TfLiteModel* model = nullptr;
    TfLiteInterpreter* interpreter = nullptr;
    TfLiteInterpreterOptions* interp_opts = nullptr;
    TfLiteDelegate* nnapi_delegate = nullptr;
    std::string model_path;
};

static NpuSession g_session;
static std::mutex g_mutex;
static bool g_tflite_resolved = false;

// ── Resolve TFLite C API symbols via dlsym ───────────────────────────────
static bool resolve_tflite_symbols() {
    if (g_tflite_resolved) return true;

    // Try loading TFLite from multiple sources:
    // 1. libtensorflowlite_jni.so (bundled by TFLite AAR dep)
    // 2. libneuronusdk_adapter.mtk.so (already loaded in process)
    // 3. RTLD_DEFAULT (any loaded library)
    void* handles[] = {
        dlopen("libtensorflowlite_jni.so", RTLD_NOW | RTLD_NOLOAD),
        dlopen("libneuronusdk_adapter.mtk.so", RTLD_NOW | RTLD_NOLOAD),
        RTLD_DEFAULT,
        nullptr
    };

    void* h = nullptr;
    const char* h_name = nullptr;
    for (int i = 0; handles[i] != nullptr; i++) {
        void* test = dlsym(handles[i], "TfLiteModelCreateFromFile");
        if (test) {
            h = handles[i];
            h_name = (i == 0) ? "libtensorflowlite_jni.so" :
                     (i == 1) ? "libneuronusdk_adapter.mtk.so" :
                     "RTLD_DEFAULT";
            break;
        }
    }

    if (!h) {
        // Last resort: try dlopen without NOLOAD
        h = dlopen("libtensorflowlite_jni.so", RTLD_NOW);
        if (!h) h = dlopen("libtensorflowlite.so", RTLD_NOW);
        if (!h) h = RTLD_DEFAULT;
        h_name = "dlopen_fallback";
    }

    LOGI("TFLite resolved from: %s (handle=%p)", h_name, h);

    auto R = [&](const char* sym, auto& fn) -> bool {
        void* p = dlsym(h, sym);
        if (p) {
            LOGI("  dlsym OK: %s", sym);
            fn = reinterpret_cast<decltype(fn)>(p);
            return true;
        } else {
            LOGE("  dlsym FAILED: %s — %s", sym, dlerror());
            return false;
        }
    };

    bool ok = true;
    ok &= R("TfLiteModelCreateFromFile",         pfn_TfLiteModelCreateFromFile);
    ok &= R("TfLiteModelDelete",                 pfn_TfLiteModelDelete);
    ok &= R("TfLiteInterpreterOptionsCreate",    pfn_TfLiteInterpreterOptionsCreate);
    ok &= R("TfLiteInterpreterOptionsDelete",    pfn_TfLiteInterpreterOptionsDelete);
    ok &= R("TfLiteInterpreterOptionsAddDelegate", pfn_TfLiteInterpreterOptionsAddDelegate);
    ok &= R("TfLiteInterpreterCreate",           pfn_TfLiteInterpreterCreate);
    ok &= R("TfLiteInterpreterDelete",           pfn_TfLiteInterpreterDelete);
    ok &= R("TfLiteInterpreterAllocateTensors",  pfn_TfLiteInterpreterAllocateTensors);
    ok &= R("TfLiteInterpreterGetInputTensorCount",  pfn_TfLiteInterpreterGetInputTensorCount);
    ok &= R("TfLiteInterpreterGetOutputTensorCount", pfn_TfLiteInterpreterGetOutputTensorCount);
    ok &= R("TfLiteInterpreterGetInputTensor",   pfn_TfLiteInterpreterGetInputTensor);
    ok &= R("TfLiteInterpreterGetOutputTensor",  pfn_TfLiteInterpreterGetOutputTensor);
    ok &= R("TfLiteInterpreterInvoke",           pfn_TfLiteInterpreterInvoke);
    ok &= R("TfLiteTensorType",                  pfn_TfLiteTensorType);
    ok &= R("TfLiteTensorByteSize",              pfn_TfLiteTensorByteSize);
    ok &= R("TfLiteTensorData",                  pfn_TfLiteTensorData);
    ok &= R("TfLiteNnapiDelegateCreate",         pfn_TfLiteNnapiDelegateCreate);
    ok &= R("TfLiteNnapiDelegateDelete",         pfn_TfLiteNnapiDelegateDelete);
    ok &= R("TfLiteNnapiDelegateOptionsDefault", pfn_TfLiteNnapiDelegateOptionsDefault);

    if (ok) {
        LOGI("All TFLite C API symbols resolved");
        g_tflite_resolved = true;
    }
    return ok;
}

// ═══════════════════════════════════════════════════════════════════════════
// JNI Methods
// ═══════════════════════════════════════════════════════════════════════════

// Called when libneuron_bridge.so is loaded
JNIEXPORT jint JNICALL JNI_OnLoad(JavaVM* vm, void* reserved) {
    LOGI("JNI_OnLoad — standard NNAPI delegate path");
    return JNI_VERSION_1_6;
}

// Check if TFLite + NNAPI delegate are available
extern "C" JNIEXPORT jboolean JNICALL
Java_com_aetheria_vance_brain_NeuronBridge_nativeIsAvailable(
    JNIEnv* env, jobject thiz)
{
    LOGI("nativeIsAvailable — probing TFLite + NNAPI");

    if (!resolve_tflite_symbols()) {
        LOGE("nativeIsAvailable: TFLite symbols not available");
        return JNI_FALSE;
    }

    if (!pfn_TfLiteNnapiDelegateCreate || !pfn_TfLiteNnapiDelegateOptionsDefault) {
        LOGE("nativeIsAvailable: NNAPI delegate symbols missing");
        return JNI_FALSE;
    }

    // Try creating a test delegate to verify NNAPI is functional
    TfLiteNnapiDelegateOptions opts;
    memset(&opts, 0, sizeof(opts));
    pfn_TfLiteNnapiDelegateOptionsDefault(&opts);
    opts.accelerator_name = "mtk-neuron_shim";
    opts.disallow_nnapi_cpu = 1;
    opts.execution_preference = 3; // kHighPerformance

    TfLiteDelegate* test_del = pfn_TfLiteNnapiDelegateCreate(&opts);
    if (test_del) {
        LOGI("nativeIsAvailable: NNAPI delegate created successfully (mtk-neuron_shim)");
        pfn_TfLiteNnapiDelegateDelete(test_del);
        return JNI_TRUE;
    }

    // Try without accelerator_name (let system pick)
    memset(&opts, 0, sizeof(opts));
    pfn_TfLiteNnapiDelegateOptionsDefault(&opts);
    opts.disallow_nnapi_cpu = 0; // Allow CPU fallback for availability check
    opts.execution_preference = 3;

    test_del = pfn_TfLiteNnapiDelegateCreate(&opts);
    if (test_del) {
        LOGI("nativeIsAvailable: NNAPI delegate created (any accelerator)");
        pfn_TfLiteNnapiDelegateDelete(test_del);
        return JNI_TRUE;
    }

    LOGW("nativeIsAvailable: NNAPI delegate creation failed — NNAPI may not be available");
    return JNI_FALSE;
}

// Initialize NPU session with model
extern "C" JNIEXPORT jlong JNICALL
Java_com_aetheria_vance_brain_NeuronBridge_nativeInit(
    JNIEnv* env, jobject thiz,
    jstring modelPath, jstring cacheDir)
{
    const char* mp = env->GetStringUTFChars(modelPath, nullptr);
    const char* cd = env->GetStringUTFChars(cacheDir, nullptr);
    std::string model_path(mp);
    std::string cache_dir(cd);
    env->ReleaseStringUTFChars(modelPath, mp);
    env->ReleaseStringUTFChars(cacheDir, cd);

    LOGI("nativeInit: model=%s cache=%s", model_path.c_str(), cache_dir.c_str());

    std::lock_guard<std::mutex> lock(g_mutex);

    if (!resolve_tflite_symbols()) {
        LOGE("nativeInit: TFLite resolve failed");
        return 0L;
    }

    // Load model from file
    g_session.model = pfn_TfLiteModelCreateFromFile(model_path.c_str());
    if (!g_session.model) {
        LOGE("nativeInit: Model load failed: %s", model_path.c_str());
        return 0L;
    }
    LOGI("Model loaded: %s", model_path.c_str());

    // Create interpreter options
    g_session.interp_opts = pfn_TfLiteInterpreterOptionsCreate();
    if (!g_session.interp_opts) {
        LOGE("nativeInit: OptionsCreate failed");
        pfn_TfLiteModelDelete(g_session.model);
        g_session.model = nullptr;
        return 0L;
    }

    // Create and attach NNAPI delegate with MTK NPU targeting
    if (pfn_TfLiteNnapiDelegateCreate && pfn_TfLiteNnapiDelegateOptionsDefault) {
        TfLiteNnapiDelegateOptions nn_opts;
        memset(&nn_opts, 0, sizeof(nn_opts));
        pfn_TfLiteNnapiDelegateOptionsDefault(&nn_opts);
        nn_opts.accelerator_name = "mtk-neuron_shim";
        nn_opts.disallow_nnapi_cpu = 1;
        nn_opts.execution_preference = 3; // kHighPerformance

        if (!cache_dir.empty()) {
            nn_opts.cache_dir = cache_dir.c_str();
        }

        g_session.nnapi_delegate = pfn_TfLiteNnapiDelegateCreate(&nn_opts);
        if (g_session.nnapi_delegate) {
            pfn_TfLiteInterpreterOptionsAddDelegate(g_session.interp_opts, g_session.nnapi_delegate);
            LOGI("NNAPI delegate attached (mtk-neuron_shim, no CPU fallback)");
        } else {
            LOGW("NNAPI delegate creation failed — will use CPU");
        }
    } else {
        LOGW("NNAPI delegate symbols not available — CPU only");
    }

    // Create interpreter
    g_session.interpreter = pfn_TfLiteInterpreterCreate(g_session.model, g_session.interp_opts);
    if (!g_session.interpreter) {
        LOGE("nativeInit: InterpreterCreate failed");
        if (g_session.nnapi_delegate) {
            pfn_TfLiteNnapiDelegateDelete(g_session.nnapi_delegate);
            g_session.nnapi_delegate = nullptr;
        }
        pfn_TfLiteInterpreterOptionsDelete(g_session.interp_opts);
        pfn_TfLiteModelDelete(g_session.model);
        g_session.interp_opts = nullptr;
        g_session.model = nullptr;
        return 0L;
    }

    // Allocate tensors
    if (pfn_TfLiteInterpreterAllocateTensors(g_session.interpreter) != kTfLiteOk) {
        LOGE("nativeInit: AllocateTensors failed");
        pfn_TfLiteInterpreterDelete(g_session.interpreter);
        if (g_session.nnapi_delegate) {
            pfn_TfLiteNnapiDelegateDelete(g_session.nnapi_delegate);
            g_session.nnapi_delegate = nullptr;
        }
        pfn_TfLiteInterpreterOptionsDelete(g_session.interp_opts);
        pfn_TfLiteModelDelete(g_session.model);
        g_session.interpreter = nullptr;
        g_session.interp_opts = nullptr;
        g_session.model = nullptr;
        return 0L;
    }

    int ic = pfn_TfLiteInterpreterGetInputTensorCount(g_session.interpreter);
    int oc = pfn_TfLiteInterpreterGetOutputTensorCount(g_session.interpreter);
    LOGI("Session ready: in=%d out=%d handle=%p", ic, oc, (void*)&g_session);
    return (jlong)(intptr_t)&g_session;
}

// Run inference
extern "C" JNIEXPORT jstring JNICALL
Java_com_aetheria_vance_brain_NeuronBridge_nativeInfer(
    JNIEnv* env, jobject thiz,
    jlong handle, jstring prompt)
{
    const char* prompt_str = env->GetStringUTFChars(prompt, nullptr);
    std::string input(prompt_str);
    env->ReleaseStringUTFChars(prompt, prompt_str);

    NpuSession* s = (NpuSession*)(intptr_t)handle;
    if (!s || !s->interpreter) {
        LOGE("nativeInfer: invalid session");
        return env->NewStringUTF("NPU_ERROR: invalid session");
    }

    TfLiteTensor* in_tensor = pfn_TfLiteInterpreterGetInputTensor(s->interpreter, 0);
    if (!in_tensor) {
        LOGE("nativeInfer: no input tensor");
        return env->NewStringUTF("NPU_ERROR: no input tensor");
    }

    size_t copyLen = std::min(input.size(), (size_t)pfn_TfLiteTensorByteSize(in_tensor));
    memcpy(pfn_TfLiteTensorData(in_tensor), input.data(), copyLen);
    LOGI("nativeInfer: input=%zu bytes", copyLen);

    TfLiteStatus status = pfn_TfLiteInterpreterInvoke(s->interpreter);
    if (status != kTfLiteOk) {
        LOGE("nativeInfer: invoke failed: %d", (int)status);
        return env->NewStringUTF("NPU_ERROR: invoke failed");
    }

    const TfLiteTensor* out_tensor = pfn_TfLiteInterpreterGetOutputTensor(s->interpreter, 0);
    if (!out_tensor) {
        LOGE("nativeInfer: no output tensor");
        return env->NewStringUTF("NPU_ERROR: no output tensor");
    }

    size_t out_bytes = pfn_TfLiteTensorByteSize(out_tensor);
    const void* out_data = pfn_TfLiteTensorData(out_tensor);
    if (!out_data || out_bytes == 0) {
        LOGE("nativeInfer: empty output");
        return env->NewStringUTF("NPU_ERROR: empty output");
    }

    // Return output as string (assumes text/byte output)
    std::string result((const char*)out_data, out_bytes);
    LOGI("nativeInfer: output=%zu bytes", out_bytes);
    return env->NewStringUTF(result.c_str());
}

// Clean up session
extern "C" JNIEXPORT void JNICALL
Java_com_aetheria_vance_brain_NeuronBridge_nativeClose(
    JNIEnv* env, jobject thiz, jlong handle)
{
    NpuSession* s = (NpuSession*)(intptr_t)handle;
    if (!s) return;

    LOGI("nativeClose: cleaning up session");

    if (s->interpreter) {
        pfn_TfLiteInterpreterDelete(s->interpreter);
        s->interpreter = nullptr;
    }
    if (s->nnapi_delegate) {
        pfn_TfLiteNnapiDelegateDelete(s->nnapi_delegate);
        s->nnapi_delegate = nullptr;
    }
    if (s->interp_opts) {
        pfn_TfLiteInterpreterOptionsDelete(s->interp_opts);
        s->interp_opts = nullptr;
    }
    if (s->model) {
        pfn_TfLiteModelDelete(s->model);
        s->model = nullptr;
    }
}
