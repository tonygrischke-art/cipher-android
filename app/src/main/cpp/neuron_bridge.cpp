// ============================================================================
// NeuronBridge — Phase 2: TFLite C API + NNAPI SL Shim (v3)
// File: app/src/main/cpp/neuron_bridge.cpp
// Target: libneuron_runtime.so on MT6878 (Dimensity 7300)
//
// v3: dlopen fallback chain with full dlsym logging + symbol probe
// Primary: libneuron_runtime.so (confirmed on Moto Edge MT6878)
// Fallback: libneuronusdk_adapter.mtk.so, libneuron_runtime.7.so
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
#include "tflite_headers/NeuralNetworksSupportLibrary.h"

#define LOG_TAG "NeuronBridge"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define LOGW(...) __android_log_print(ANDROID_LOG_WARN, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

// ── Global neuron library handle (loaded once in JNI_OnLoad) ──────────────
static void* g_neuron_handle = nullptr;
static const char* g_loaded_lib = nullptr;

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
static TfLiteDelegate* (*pfn_TfLiteNnapiDelegateCreate)(const void*) = nullptr;
static void (*pfn_TfLiteNnapiDelegateDelete)(TfLiteDelegate*) = nullptr;

// ── NNAPI adapter function pointers ──────────────────────────────────────
struct NnApiShims {
    int (*Memory_createFromFd)(size_t, int, int, size_t, ANeuralNetworksMemory**) = nullptr;
    int (*Compilation_createForDevices)(ANeuralNetworksModel*, const ANeuralNetworksDevice* const*, uint32_t, ANeuralNetworksCompilation**) = nullptr;
    int (*Compilation_finish)(ANeuralNetworksCompilation*) = nullptr;
    int (*Execution_create)(ANeuralNetworksCompilation*, ANeuralNetworksExecution**) = nullptr;
    int (*Execution_setInput)(ANeuralNetworksExecution*, int32_t, const ANeuralNetworksOperandType*, const void*, size_t) = nullptr;
    int (*Execution_setOutput)(ANeuralNetworksExecution*, int32_t, const ANeuralNetworksOperandType*, void*, size_t) = nullptr;
    int (*Execution_compute)(ANeuralNetworksExecution*) = nullptr;
    int (*Execution_startCompute)(ANeuralNetworksExecution*, ANeuralNetworksEvent**) = nullptr;
    int (*Model_create)(ANeuralNetworksModel**) = nullptr;
    int (*Model_addOperand)(ANeuralNetworksModel*, const ANeuralNetworksOperandType*) = nullptr;
    int (*Model_setOperandValue)(ANeuralNetworksModel*, int32_t, const void*, size_t) = nullptr;
    int (*Model_identifyInputsAndOutputs)(ANeuralNetworksModel*, uint32_t, const uint32_t*, uint32_t, const uint32_t*) = nullptr;
    int (*Model_finish)(ANeuralNetworksModel*) = nullptr;
    void (*Model_free)(ANeuralNetworksModel*) = nullptr;
    void (*Compilation_free)(ANeuralNetworksCompilation*) = nullptr;
    void (*Execution_free)(ANeuralNetworksExecution*) = nullptr;
    int (*Device_getName)(const ANeuralNetworksDevice*, const char**) = nullptr;
    int (*Device_getType)(const ANeuralNetworksDevice*, int32_t*) = nullptr;
    int (*Device_wait)(const ANeuralNetworksDevice*) = nullptr;
    int (*getDeviceCount)(uint32_t*) = nullptr;
    int (*getDevice)(uint32_t, ANeuralNetworksDevice**) = nullptr;
    int (*Event_wait)(ANeuralNetworksEvent*) = nullptr;
    void (*Event_free)(ANeuralNetworksEvent*) = nullptr;
};

static NnApiShims g_adapter;

// ── NNAPI SL shim ────────────────────────────────────────────────────────
static NnApiSLDriverImplFL5 g_nnapi_sl{};
static bool g_nnapi_sl_populated = false;

// ── Session state ─────────────────────────────────────────────────────────
struct NpuSession {
    void* adapter_handle = nullptr;
    TfLiteModel* model = nullptr;
    TfLiteInterpreter* interpreter = nullptr;
    TfLiteInterpreterOptions* interp_opts = nullptr;
    std::string model_path;
};

static NpuSession g_session;
static std::mutex g_mutex;

// ── Step 2: dlopen fallback chain with logging ────────────────────────────
static void* try_load_neuron() {
    // Try with full paths first (matches what nativeloader uses), then bare name
    const char* libs[] = {
        "/vendor/lib64/libneuron_runtime.so",  // Moto Edge MT6878 (primary, confirmed via ls)
        "/vendor/lib64/mt6878/libneuron_runtime.so",  // Direct path (follows symlink)
        "/system/lib64/libneuron_runtime.so",
        "libneuron_runtime.so",
        "/vendor/lib64/libneuronusdk_adapter.mtk.so",
        "/system/lib64/libneuronusdk_adapter.mtk.so",
        "libneuronusdk_adapter.mtk.so",
        "libneuron_runtime.7.so",
        "libneuron_adapter_mgvi.so",
        nullptr
    };

    for (int i = 0; libs[i] != nullptr; i++) {
        void* handle = dlopen(libs[i], RTLD_NOW);
        if (handle) {
            LOGI("dlopen SUCCESS: %s (handle=%p)", libs[i], handle);
            g_loaded_lib = libs[i];
            return handle;
        }
        LOGW("dlopen FAILED: %s — %s", libs[i], dlerror() ? dlerror() : "unknown");
    }

    LOGE("All dlopen attempts failed — NPU unavailable");
    return nullptr;
}

// ── Step 3: dlsym with logging ───────────────────────────────────────────
static bool resolve_tflite_symbols() {
    // Try loading TFLite from dedicated lib first, then RTLD_DEFAULT
    void* h = dlopen("libtensorflowlite_jni.so", RTLD_NOW);
    if (!h) h = dlopen("libtensorflowlite.so", RTLD_NOW);
    if (!h) h = RTLD_DEFAULT;

    LOGI("TFLite handle: %p", h);

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
    ok &= R("TfLiteModelDelete",                pfn_TfLiteModelDelete);
    ok &= R("TfLiteInterpreterOptionsCreate",   pfn_TfLiteInterpreterOptionsCreate);
    ok &= R("TfLiteInterpreterOptionsDelete",   pfn_TfLiteInterpreterOptionsDelete);
    ok &= R("TfLiteInterpreterOptionsAddDelegate", pfn_TfLiteInterpreterOptionsAddDelegate);
    ok &= R("TfLiteInterpreterCreate",          pfn_TfLiteInterpreterCreate);
    ok &= R("TfLiteInterpreterDelete",          pfn_TfLiteInterpreterDelete);
    ok &= R("TfLiteInterpreterAllocateTensors", pfn_TfLiteInterpreterAllocateTensors);
    ok &= R("TfLiteInterpreterGetInputTensorCount",  pfn_TfLiteInterpreterGetInputTensorCount);
    ok &= R("TfLiteInterpreterGetOutputTensorCount", pfn_TfLiteInterpreterGetOutputTensorCount);
    ok &= R("TfLiteInterpreterGetInputTensor",  pfn_TfLiteInterpreterGetInputTensor);
    ok &= R("TfLiteInterpreterGetOutputTensor", pfn_TfLiteInterpreterGetOutputTensor);
    ok &= R("TfLiteInterpreterInvoke",          pfn_TfLiteInterpreterInvoke);
    ok &= R("TfLiteTensorType",                 pfn_TfLiteTensorType);
    ok &= R("TfLiteTensorByteSize",             pfn_TfLiteTensorByteSize);
    ok &= R("TfLiteTensorData",                 pfn_TfLiteTensorData);
    ok &= R("TfLiteNnapiDelegateCreate",        pfn_TfLiteNnapiDelegateCreate);
    ok &= R("TfLiteNnapiDelegateDelete",        pfn_TfLiteNnapiDelegateDelete);

    if (ok) LOGI("All TFLite C API symbols resolved");
    return ok;
}

static bool resolve_neuron_symbols() {
    if (!g_neuron_handle) {
        LOGE("resolve_neuron_symbols: no g_neuron_handle");
        return false;
    }

    auto R = [&](const char* sym, auto& fn) -> bool {
        void* p = dlsym(g_neuron_handle, sym);
        if (p) {
            LOGI("  dlsym OK: %s", sym);
            fn = reinterpret_cast<decltype(fn)>(p);
            return true;
        } else {
            LOGE("  dlsym FAILED: %s — %s", sym, dlerror());
            return false;
        }
    };

    memset(&g_adapter, 0, sizeof(g_adapter));
    bool ok = true;
    ok &= R("ANeuralNetworksMemory_createFromFd",      g_adapter.Memory_createFromFd);
    ok &= R("ANeuralNetworksCompilation_createForDevices", g_adapter.Compilation_createForDevices);
    ok &= R("ANeuralNetworksCompilation_finish",       g_adapter.Compilation_finish);
    ok &= R("ANeuralNetworksExecution_create",          g_adapter.Execution_create);
    ok &= R("ANeuralNetworksExecution_setInput",        g_adapter.Execution_setInput);
    ok &= R("ANeuralNetworksExecution_setOutput",       g_adapter.Execution_setOutput);
    ok &= R("ANeuralNetworksExecution_compute",         g_adapter.Execution_compute);
    ok &= R("ANeuralNetworksExecution_startCompute",    g_adapter.Execution_startCompute);
    ok &= R("ANeuralNetworksModel_create",              g_adapter.Model_create);
    ok &= R("ANeuralNetworksModel_addOperand",          g_adapter.Model_addOperand);
    ok &= R("ANeuralNetworksModel_setOperandValue",     g_adapter.Model_setOperandValue);
    ok &= R("ANeuralNetworksModel_identifyInputsAndOutputs", g_adapter.Model_identifyInputsAndOutputs);
    ok &= R("ANeuralNetworksModel_finish",              g_adapter.Model_finish);
    ok &= R("ANeuralNetworksModel_free",                g_adapter.Model_free);
    ok &= R("ANeuralNetworksCompilation_free",         g_adapter.Compilation_free);
    ok &= R("ANeuralNetworksExecution_free",           g_adapter.Execution_free);
    ok &= R("ANeuralNetworksDevice_getName",           g_adapter.Device_getName);
    ok &= R("ANeuralNetworksDevice_getType",           g_adapter.Device_getType);
    ok &= R("ANeuralNetworksDevice_wait",              g_adapter.Device_wait);
    ok &= R("ANeuralNetworks_getDeviceCount",          g_adapter.getDeviceCount);
    ok &= R("ANeuralNetworks_getDevice",               g_adapter.getDevice);
    ok &= R("ANeuralNetworksEvent_wait",               g_adapter.Event_wait);
    ok &= R("ANeuralNetworksEvent_free",               g_adapter.Event_free);

    return ok;
}

// ── NNAPI SL shim wrappers ───────────────────────────────────────────────
static int w_getDeviceCount(uint32_t* n) { return g_adapter.getDeviceCount(n); }
static int w_getDevice(uint32_t i, ANeuralNetworksDevice** d) { return g_adapter.getDevice(i, d); }
static int w_Device_getName(const ANeuralNetworksDevice* d, const char** n) { return g_adapter.Device_getName(d, n); }
static int w_Device_getType(const ANeuralNetworksDevice* d, int32_t* t) { return g_adapter.Device_getType(d, t); }
static int w_Device_wait(const ANeuralNetworksDevice* d) { return g_adapter.Device_wait(d); }
static int w_Model_create(ANeuralNetworksModel** m) { return g_adapter.Model_create(m); }
static void w_Model_free(ANeuralNetworksModel* m) { g_adapter.Model_free(m); }
static int w_Model_addOperand(ANeuralNetworksModel* m, const ANeuralNetworksOperandType* t) { return g_adapter.Model_addOperand(m, t); }
static int w_Model_setOperandValue(ANeuralNetworksModel* m, int32_t i, const void* b, size_t l) { return g_adapter.Model_setOperandValue(m, i, b, l); }
static int w_Model_identifyInputsAndOutputs(ANeuralNetworksModel* m, uint32_t ic, const uint32_t* i, uint32_t oc, const uint32_t* o) { return g_adapter.Model_identifyInputsAndOutputs(m, ic, i, oc, o); }
static int w_Model_finish(ANeuralNetworksModel* m) { return g_adapter.Model_finish(m); }
static int w_Memory_createFromFd(size_t s, int p, int fd, size_t o, ANeuralNetworksMemory** m) { return g_adapter.Memory_createFromFd(s, p, fd, o, m); }
static int w_Compilation_createForDevices(ANeuralNetworksModel* m, const ANeuralNetworksDevice* const* d, uint32_t n, ANeuralNetworksCompilation** c) {
    LOGI("Compilation: model=%p numDevices=%u", (void*)m, n);
    return g_adapter.Compilation_createForDevices(m, d, n, c);
}
static void w_Compilation_free(ANeuralNetworksCompilation* c) { g_adapter.Compilation_free(c); }
static int w_Compilation_finish(ANeuralNetworksCompilation* c) { return g_adapter.Compilation_finish(c); }
static int w_Execution_create(ANeuralNetworksCompilation* c, ANeuralNetworksExecution** e) { return g_adapter.Execution_create(c, e); }
static void w_Execution_free(ANeuralNetworksExecution* e) { g_adapter.Execution_free(e); }
static int w_Execution_setInput(ANeuralNetworksExecution* e, int32_t i, const ANeuralNetworksOperandType* t, const void* b, size_t l) { return g_adapter.Execution_setInput(e, i, t, b, l); }
static int w_Execution_setOutput(ANeuralNetworksExecution* e, int32_t i, const ANeuralNetworksOperandType* t, void* b, size_t l) { return g_adapter.Execution_setOutput(e, i, t, b, l); }
static int w_Execution_compute(ANeuralNetworksExecution* e) {
    int ret = g_adapter.Execution_compute(e);
    LOGI("Execution_compute returned: %d", ret);
    return ret;
}
static int w_Execution_startCompute(ANeuralNetworksExecution* e, ANeuralNetworksEvent** ev) { return g_adapter.Execution_startCompute(e, ev); }
static int w_Event_wait(ANeuralNetworksEvent* e) { return g_adapter.Event_wait(e); }
static void w_Event_free(ANeuralNetworksEvent* e) { g_adapter.Event_free(e); }

static void populateNnApiSL() {
    if (g_nnapi_sl_populated) return;
    memset(&g_nnapi_sl, 0, sizeof(g_nnapi_sl));
    g_nnapi_sl.base.implFeatureLevel = ANEURALNETWORKS_FEATURE_LEVEL_5;
    g_nnapi_sl.ANeuralNetworks_getDeviceCount = w_getDeviceCount;
    g_nnapi_sl.ANeuralNetworks_getDevice = w_getDevice;
    g_nnapi_sl.ANeuralNetworksDevice_getName = w_Device_getName;
    g_nnapi_sl.ANeuralNetworksDevice_getType = w_Device_getType;
    g_nnapi_sl.ANeuralNetworksDevice_wait = w_Device_wait;
    g_nnapi_sl.ANeuralNetworksModel_create = w_Model_create;
    g_nnapi_sl.ANeuralNetworksModel_free = w_Model_free;
    g_nnapi_sl.ANeuralNetworksModel_addOperand = w_Model_addOperand;
    g_nnapi_sl.ANeuralNetworksModel_setOperandValue = w_Model_setOperandValue;
    g_nnapi_sl.ANeuralNetworksModel_identifyInputsAndOutputs = w_Model_identifyInputsAndOutputs;
    g_nnapi_sl.ANeuralNetworksModel_finish = w_Model_finish;
    g_nnapi_sl.ANeuralNetworksMemory_createFromFd = w_Memory_createFromFd;
    g_nnapi_sl.ANeuralNetworksCompilation_createForDevices = w_Compilation_createForDevices;
    g_nnapi_sl.ANeuralNetworksCompilation_free = w_Compilation_free;
    g_nnapi_sl.ANeuralNetworksCompilation_finish = w_Compilation_finish;
    g_nnapi_sl.ANeuralNetworksExecution_create = w_Execution_create;
    g_nnapi_sl.ANeuralNetworksExecution_free = w_Execution_free;
    g_nnapi_sl.ANeuralNetworksExecution_setInput = w_Execution_setInput;
    g_nnapi_sl.ANeuralNetworksExecution_setOutput = w_Execution_setOutput;
    g_nnapi_sl.ANeuralNetworksExecution_compute = w_Execution_compute;
    g_nnapi_sl.ANeuralNetworksExecution_startCompute = w_Execution_startCompute;
    g_nnapi_sl.ANeuralNetworksEvent_wait = w_Event_wait;
    g_nnapi_sl.ANeuralNetworksEvent_free = w_Event_free;
    g_nnapi_sl_populated = true;
    LOGI("NnApiSL shim populated (FL5, %zu bytes)", sizeof(g_nnapi_sl));
}

// ── Step 4: Symbol discovery probe ────────────────────────────────────────
static void log_available_symbols() {
    if (!g_neuron_handle) return;

    const char* probe_symbols[] = {
        "NeuronCompilation_create",
        "NeuronModel_create",
        "NeuronExecution_create",
        "ANeuralNetworksCompilation_create",
        "ANeuralNetworksModel_create",
        "NeuronRuntime_create",
        "neuron_create",
        "mtk_neuron_init",
        "NeuroPilotTFLiteOptions_create",
        "NeuroPilotTFLite_create",
        nullptr
    };

    LOGI("--- Symbol probe ---");
    for (int i = 0; probe_symbols[i] != nullptr; i++) {
        void* sym = dlsym(g_neuron_handle, probe_symbols[i]);
        LOGI("  %s: %s", probe_symbols[i], sym ? "FOUND" : "absent");
    }
    LOGI("--- End probe ---");
}

// ═══════════════════════════════════════════════════════════════════════════
// JNI Methods
// ═══════════════════════════════════════════════════════════════════════════

// Step 2: JNI_OnLoad — minimal, no symbol resolution here
JNIEXPORT jint JNICALL JNI_OnLoad(JavaVM* vm, void* reserved) {
    LOGI("JNI_OnLoad called");
    g_neuron_handle = try_load_neuron();
    LOGI("NPU handle: %s (lib: %s)", g_neuron_handle ? "VALID" : "NULL", g_loaded_lib ? g_loaded_lib : "none");
    return JNI_VERSION_1_6;
}

// Step 5: nativeIsAvailable with logging
extern "C" JNIEXPORT jboolean JNICALL
Java_com_aetheria_vance_brain_NeuronBridge_nativeIsAvailable(
    JNIEnv* env, jobject thiz)
{
    LOGI("nativeIsAvailable called — handle: %s, lib: %s",
         g_neuron_handle ? "VALID" : "NULL",
         g_loaded_lib ? g_loaded_lib : "none");

    if (!g_neuron_handle) {
        LOGE("nativeIsAvailable: no neuron handle");
        return JNI_FALSE;
    }

    // Resolve neuron symbols
    if (!resolve_neuron_symbols()) {
        LOGE("nativeIsAvailable: resolve_neuron_symbols failed");
        return JNI_FALSE;
    }

    // Also check TFLite
    if (!resolve_tflite_symbols()) {
        LOGE("nativeIsAvailable: resolve_tflite_symbols failed");
        return JNI_FALSE;
    }

    // Check for NPU device
    uint32_t deviceCount = 0;
    if (g_adapter.getDeviceCount && g_adapter.getDeviceCount(&deviceCount) == 0) {
        LOGI("Device count: %u", deviceCount);
        if (deviceCount > 0) {
            for (uint32_t i = 0; i < deviceCount; ++i) {
                ANeuralNetworksDevice* device = nullptr;
                if (g_adapter.getDevice(i, &device) == 0 && device) {
                    int32_t type = -1;
                    const char* name = nullptr;
                    g_adapter.Device_getType(device, &type);
                    g_adapter.Device_getName(device, &name);
                    LOGI("  Device[%u]: name=%s type=%d", i, name ? name : "?", type);
                    if (type == 3) {  // ANEURALNETWORKS_DEVICE_TYPE_NPU
                        LOGI("nativeIsAvailable: NPU device found");
                        return JNI_TRUE;
                    }
                }
            }
        }
    }

    LOGI("nativeIsAvailable: No NPU device found (but symbols resolved)");
    return JNI_FALSE;
}

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

    LOGI("nativeInit: model=%s lib=%s", model_path.c_str(), g_loaded_lib ? g_loaded_lib : "none");

    std::lock_guard<std::mutex> lock(g_mutex);

    // Use the already-loaded handle from JNI_OnLoad
    if (!g_neuron_handle) {
        LOGE("nativeInit: no neuron handle — call nativeIsAvailable first");
        return 0L;
    }

    g_session.adapter_handle = g_neuron_handle;

    // Load TFLite if not already
    if (!resolve_tflite_symbols()) {
        LOGE("nativeInit: TFLite resolve failed");
        return 0L;
    }

    // Populate SL shim
    populateNnApiSL();

    // Load model
    g_session.model = pfn_TfLiteModelCreateFromFile(model_path.c_str());
    if (!g_session.model) {
        LOGE("nativeInit: Model load failed: %s", model_path.c_str());
        return 0L;
    }
    LOGI("Model loaded: %s", model_path.c_str());

    // Create options + attach NNAPI delegate
    g_session.interp_opts = pfn_TfLiteInterpreterOptionsCreate();
    if (!g_session.interp_opts) {
        LOGE("nativeInit: OptionsCreate failed");
        pfn_TfLiteModelDelete(g_session.model);
        g_session.model = nullptr;
        return 0L;
    }

    if (pfn_TfLiteNnapiDelegateCreate) {
        TfLiteDelegate* del = pfn_TfLiteNnapiDelegateCreate(nullptr);
        if (del) {
            pfn_TfLiteInterpreterOptionsAddDelegate(g_session.interp_opts, del);
            LOGI("NnApiDelegate created and added");
        } else {
            LOGW("NnApiDelegateCreate returned null — CPU fallback");
        }
    } else {
        LOGW("NnApiDelegateCreate not resolved — CPU fallback");
    }

    // Create interpreter
    g_session.interpreter = pfn_TfLiteInterpreterCreate(g_session.model, g_session.interp_opts);
    if (!g_session.interpreter) {
        LOGE("nativeInit: InterpreterCreate failed");
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
        pfn_TfLiteInterpreterOptionsDelete(g_session.interp_opts);
        pfn_TfLiteModelDelete(g_session.model);
        g_session.interpreter = nullptr;
        g_session.interp_opts = nullptr;
        g_session.model = nullptr;
        return 0L;
    }

    int ic = pfn_TfLiteInterpreterGetInputTensorCount(g_session.interpreter);
    int oc = pfn_TfLiteInterpreterGetOutputTensorCount(g_session.interpreter);
    LOGI("Tensors: in=%d out=%d — Session ready: %p", ic, oc, (void*)&g_session);
    return (jlong)(intptr_t)&g_session;
}

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
        return env->NewStringUTF("APU_ERROR: invalid session");
    }

    TfLiteTensor* in_tensor = pfn_TfLiteInterpreterGetInputTensor(s->interpreter, 0);
    if (!in_tensor) {
        LOGE("nativeInfer: no input tensor");
        return env->NewStringUTF("APU_ERROR: no input tensor");
    }

    size_t copyLen = std::min(input.size(), (size_t)pfn_TfLiteTensorByteSize(in_tensor));
    memcpy(pfn_TfLiteTensorData(in_tensor), input.data(), copyLen);
    LOGI("nativeInfer: input=%zu bytes", copyLen);

    TfLiteStatus status = pfn_TfLiteInterpreterInvoke(s->interpreter);
    if (status != kTfLiteOk) {
        LOGE("nativeInvoke failed: %d", (int)status);
        return env->NewStringUTF("APU_ERROR: invoke failed");
    }

    const TfLiteTensor* out_tensor = pfn_TfLiteInterpreterGetOutputTensor(s->interpreter, 0);
    if (!out_tensor) {
        LOGE("nativeInfer: no output tensor");
        return env->NewStringUTF("APU_ERROR: no output tensor");
    }

    const void* outData = pfn_TfLiteTensorData(out_tensor);
    size_t outBytes = pfn_TfLiteTensorByteSize(out_tensor);
    TfLiteType outType = pfn_TfLiteTensorType(out_tensor);
    std::string result;

    if (outType == kTfLiteInt32 && outBytes >= 4 * sizeof(int32_t)) {
        int32_t* v = (int32_t*)outData;
        char buf[256];
        snprintf(buf, sizeof(buf), "[%d, %d, %d, %d]", v[0], v[1], v[2], v[3]);
        result = buf;
    } else if (outType == kTfLiteFloat32 && outBytes >= 4 * sizeof(float)) {
        float* v = (float*)outData;
        char buf[256];
        snprintf(buf, sizeof(buf), "[%.1f, %.1f, %.1f, %.1f]", v[0], v[1], v[2], v[3]);
        result = buf;
    } else {
        char buf[256];
        snprintf(buf, sizeof(buf), "APU_OUTPUT: type=%d bytes=%zu", (int)outType, outBytes);
        result = buf;
    }

    LOGI("nativeInfer result: %s", result.c_str());
    return env->NewStringUTF(result.c_str());
}

extern "C" JNIEXPORT void JNICALL
Java_com_aetheria_vance_brain_NeuronBridge_nativeClose(
    JNIEnv* env, jobject thiz, jlong handle)
{
    NpuSession* s = (NpuSession*)(intptr_t)handle;
    if (!s) return;
    std::lock_guard<std::mutex> lock(g_mutex);
    if (s->interpreter) { pfn_TfLiteInterpreterDelete(s->interpreter); s->interpreter = nullptr; }
    if (s->interp_opts) { pfn_TfLiteInterpreterOptionsDelete(s->interp_opts); s->interp_opts = nullptr; }
    if (s->model) { pfn_TfLiteModelDelete(s->model); s->model = nullptr; }
    s->adapter_handle = nullptr;
    LOGI("Session closed: %p", (void*)s);
}
