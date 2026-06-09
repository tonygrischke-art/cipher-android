// ============================================================================
// NeuronBridge — Phase 2: TFLite C API + NNAPI SL Shim
// File: app/src/main/cpp/neuron_bridge.cpp
// Target: libneuron_adapter_mgvi.so on MT6878 (Dimensity 7300/8200)
//
// Architecture:
//   TfLiteInterpreterInvoke()
//     → NnApiDelegate (TFLite handles model parsing)
//       → g_nnapi_sl.ANeuralNetworksCompilation_createForDevices  ← our shim
//         → libneuron_adapter_mgvi.so  ← vendor lib
//           → APU 650 hardware
//
// Both TFLite C API and NNAPI adapter are loaded via dlopen/dlsym at runtime.
// No build-time linking against TFLite native library needed.
// ============================================================================

#include <jni.h>
#include <dlfcn.h>
#include <android/log.h>
#include <string>
#include <vector>
#include <mutex>
#include <cstring>

// ── TFLite C API headers (types/structs only, functions via dlsym) ───────
#include "tflite_headers/c_api.h"
#include "tflite_headers/c_api_experimental.h"
#include "tflite_headers/common.h"
#include "tflite_headers/nnapi_delegate_c_api.h"
#include "tflite_headers/NeuralNetworksSupportLibrary.h"

#define LOG_TAG "APU"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO,  LOG_TAG, __VA_ARGS__)
#define LOGD(...) __android_log_print(ANDROID_LOG_DEBUG, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

// ═══════════════════════════════════════════════════════════════════════════
// TFLite C API function pointers (loaded via dlsym)
// ═══════════════════════════════════════════════════════════════════════════

#define DECL_TFLITE(ret, name, args) \
    static ret (*pfn_##name) args = nullptr;

// Model
DECL_TFLITE(TfLiteModel*, TfLiteModelCreateFromFile, (const char*))
DECL_TFLITE(void, TfLiteModelDelete, (TfLiteModel*))

// Interpreter Options
DECL_TFLITE(TfLiteInterpreterOptions*, TfLiteInterpreterOptionsCreate, (void))
DECL_TFLITE(void, TfLiteInterpreterOptionsDelete, (TfLiteInterpreterOptions*))
DECL_TFLITE(void, TfLiteInterpreterOptionsAddDelegate, (TfLiteInterpreterOptions*, TfLiteDelegate*))

// Interpreter
DECL_TFLITE(TfLiteInterpreter*, TfLiteInterpreterCreate, (TfLiteModel*, const TfLiteInterpreterOptions*))
DECL_TFLITE(void, TfLiteInterpreterDelete, (TfLiteInterpreter*))
DECL_TFLITE(TfLiteStatus, TfLiteInterpreterAllocateTensors, (TfLiteInterpreter*))
DECL_TFLITE(int32_t, TfLiteInterpreterGetInputTensorCount, (const TfLiteInterpreter*))
DECL_TFLITE(int32_t, TfLiteInterpreterGetOutputTensorCount, (const TfLiteInterpreter*))
DECL_TFLITE(TfLiteTensor*, TfLiteInterpreterGetInputTensor, (const TfLiteInterpreter*, int32_t))
DECL_TFLITE(const TfLiteTensor*, TfLiteInterpreterGetOutputTensor, (const TfLiteInterpreter*, int32_t))
DECL_TFLITE(TfLiteStatus, TfLiteInterpreterInvoke, (TfLiteInterpreter*))

// Tensor
DECL_TFLITE(TfLiteType, TfLiteTensorType, (const TfLiteTensor*))
DECL_TFLITE(size_t, TfLiteTensorByteSize, (const TfLiteTensor*))
DECL_TFLITE(void*, TfLiteTensorData, (const TfLiteTensor*))

// NNAPI Delegate
DECL_TFLITE(TfLiteNnApiDelegateParams*, TfLiteNnApiDelegateParamsCreate, (void))
DECL_TFLITE(void, TfLiteNnApiDelegateParamsDelete, (TfLiteNnApiDelegateParams*))
DECL_TFLITE(void, TfLiteNnApiDelegateParamsSetSupportLibraryHandle, (TfLiteNnApiDelegateParams*, void*))
DECL_TFLITE(TfLiteDelegate*, TfLiteNnApiDelegateCreate, (const TfLiteNnApiDelegateParams*))
DECL_TFLITE(void, TfLiteNnApiDelegateDelete, (TfLiteDelegate*))

#undef DECL_TFLITE

// ═══════════════════════════════════════════════════════════════════════════
// NNAPI adapter function pointers (loaded from libneuron_adapter_mgvi.so)
// ═══════════════════════════════════════════════════════════════════════════

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

// ═══════════════════════════════════════════════════════════════════════════
// NNAPI SL shim struct (populated from g_adapter)
// ═══════════════════════════════════════════════════════════════════════════

static NnApiSLDriverImplFL5 g_nnapi_sl{};
static bool g_nnapi_sl_populated = false;

// ── Session state ─────────────────────────────────────────────────────────
struct NpuSession {
    void* tflite_handle = nullptr;        // dlopen handle for libtensorflowlite_jni.so
    void* adapter_handle = nullptr;       // dlopen handle for libneuron_adapter_mgvi.so
    TfLiteModel* model = nullptr;
    TfLiteInterpreter* interpreter = nullptr;
    TfLiteInterpreterOptions* interp_opts = nullptr;
    std::string model_path;
};

static std::mutex g_mutex;

// ═══════════════════════════════════════════════════════════════════════════
// Load TFLite shared library and resolve all C API symbols
// ═══════════════════════════════════════════════════════════════════════════

static bool loadTFLite() {
    static bool loaded = false;
    static bool success = false;
    if (loaded) return success;
    loaded = true;

    const char* paths[] = {
        "libtensorflowlite_jni.so",
        "libtensorflowlite.so",
    };
    void* h = nullptr;
    for (auto p : paths) {
        h = dlopen(p, RTLD_NOW);
        if (h) { LOGI("[APU] Loaded TFLite from: %s", p); break; }
    }
    if (!h) {
        // Last resort: search RTLD_DEFAULT (if TFLite is linked into the process)
        h = RTLD_DEFAULT;
        LOGI("[APU] Trying RTLD_DEFAULT for TFLite symbols");
    }

    // Resolve all TFLite C API symbols
    auto resolve = [&](const char* name, auto& fn) -> bool {
        fn = (decltype(fn))dlsym(h, name);
        if (!fn) { LOGE("[APU] dlsym(%s) failed: %s", name, dlerror()); return false; }
        return true;
    };

    bool ok = true;
    ok &= resolve("TfLiteModelCreateFromFile", pfn_TfLiteModelCreateFromFile);
    ok &= resolve("TfLiteModelDelete", pfn_TfLiteModelDelete);
    ok &= resolve("TfLiteInterpreterOptionsCreate", pfn_TfLiteInterpreterOptionsCreate);
    ok &= resolve("TfLiteInterpreterOptionsDelete", pfn_TfLiteInterpreterOptionsDelete);
    ok &= resolve("TfLiteInterpreterOptionsAddDelegate", pfn_TfLiteInterpreterOptionsAddDelegate);
    ok &= resolve("TfLiteInterpreterCreate", pfn_TfLiteInterpreterCreate);
    ok &= resolve("TfLiteInterpreterDelete", pfn_TfLiteInterpreterDelete);
    ok &= resolve("TfLiteInterpreterAllocateTensors", pfn_TfLiteInterpreterAllocateTensors);
    ok &= resolve("TfLiteInterpreterGetInputTensorCount", pfn_TfLiteInterpreterGetInputTensorCount);
    ok &= resolve("TfLiteInterpreterGetOutputTensorCount", pfn_TfLiteInterpreterGetOutputTensorCount);
    ok &= resolve("TfLiteInterpreterGetInputTensor", pfn_TfLiteInterpreterGetInputTensor);
    ok &= resolve("TfLiteInterpreterGetOutputTensor", pfn_TfLiteInterpreterGetOutputTensor);
    ok &= resolve("TfLiteInterpreterInvoke", pfn_TfLiteInterpreterInvoke);
    ok &= resolve("TfLiteTensorType", pfn_TfLiteTensorType);
    ok &= resolve("TfLiteTensorByteSize", pfn_TfLiteTensorByteSize);
    ok &= resolve("TfLiteTensorData", pfn_TfLiteTensorData);
    ok &= resolve("TfLiteNnApiDelegateParamsCreate", pfn_TfLiteNnApiDelegateParamsCreate);
    ok &= resolve("TfLiteNnApiDelegateParamsDelete", pfn_TfLiteNnApiDelegateParamsDelete);
    ok &= resolve("TfLiteNnApiDelegateParamsSetSupportLibraryHandle", pfn_TfLiteNnApiDelegateParamsSetSupportLibraryHandle);
    ok &= resolve("TfLiteNnApiDelegateCreate", pfn_TfLiteNnApiDelegateCreate);
    ok &= resolve("TfLiteNnApiDelegateDelete", pfn_TfLiteNnApiDelegateDelete);

    if (ok) {
        LOGI("[APU] All TFLite C API symbols resolved ✓");
        success = true;
    } else {
        LOGE("[APU] Failed to resolve some TFLite symbols");
    }
    return success;
}

// ═══════════════════════════════════════════════════════════════════════════
// Load NNAPI adapter (libneuron_adapter_mgvi.so)
// ═══════════════════════════════════════════════════════════════════════════

static void* loadAdapter() {
    static void* h = nullptr;
    if (h) return h;
    const char* paths[] = {
        "/vendor/lib64/libneuron_adapter_mgvi.so",
        "/system/lib64/libneuron_adapter_mgvi.so",
        "/vendor/lib/libneuron_adapter_mgvi.so",
        "libneuron_adapter_mgvi.so"
    };
    for (auto p : paths) {
        h = dlopen(p, RTLD_NOW);
        if (h) { LOGI("[APU] Loaded adapter from: %s", p); return h; }
    }
    LOGE("[APU] Cannot load libneuron_adapter_mgvi.so: %s", dlerror());
    return nullptr;
}

static bool resolveAdapter(void* h) {
    auto R = [&](const char* name, auto& fn) -> bool {
        fn = (decltype(fn))dlsym(h, name);
        if (!fn) { LOGE("[APU] dlsym(%s) failed: %s", name, dlerror()); return false; }
        return true;
    };
    bool ok = true;
    ok &= R("ANeuralNetworksMemory_createFromFd", g_adapter.Memory_createFromFd);
    ok &= R("ANeuralNetworksCompilation_createForDevices", g_adapter.Compilation_createForDevices);
    ok &= R("ANeuralNetworksCompilation_finish", g_adapter.Compilation_finish);
    ok &= R("ANeuralNetworksExecution_create", g_adapter.Execution_create);
    ok &= R("ANeuralNetworksExecution_setInput", g_adapter.Execution_setInput);
    ok &= R("ANeuralNetworksExecution_setOutput", g_adapter.Execution_setOutput);
    ok &= R("ANeuralNetworksExecution_compute", g_adapter.Execution_compute);
    ok &= R("ANeuralNetworksExecution_startCompute", g_adapter.Execution_startCompute);
    ok &= R("ANeuralNetworksModel_create", g_adapter.Model_create);
    ok &= R("ANeuralNetworksModel_addOperand", g_adapter.Model_addOperand);
    ok &= R("ANeuralNetworksModel_setOperandValue", g_adapter.Model_setOperandValue);
    ok &= R("ANeuralNetworksModel_identifyInputsAndOutputs", g_adapter.Model_identifyInputsAndOutputs);
    ok &= R("ANeuralNetworksModel_finish", g_adapter.Model_finish);
    ok &= R("ANeuralNetworksModel_free", g_adapter.Model_free);
    ok &= R("ANeuralNetworksCompilation_free", g_adapter.Compilation_free);
    ok &= R("ANeuralNetworksExecution_free", g_adapter.Execution_free);
    ok &= R("ANeuralNetworksDevice_getName", g_adapter.Device_getName);
    ok &= R("ANeuralNetworksDevice_getType", g_adapter.Device_getType);
    ok &= R("ANeuralNetworksDevice_wait", g_adapter.Device_wait);
    ok &= R("ANeuralNetworks_getDeviceCount", g_adapter.getDeviceCount);
    ok &= R("ANeuralNetworks_getDevice", g_adapter.getDevice);
    ok &= R("ANeuralNetworksEvent_wait", g_adapter.Event_wait);
    ok &= R("ANeuralNetworksEvent_free", g_adapter.Event_free);
    return ok;
}

// ═══════════════════════════════════════════════════════════════════════════
// NNAPI SL shim — wrapper functions that forward to g_adapter
// ═══════════════════════════════════════════════════════════════════════════

static int wrap_getDeviceCount(uint32_t* n) { return g_adapter.getDeviceCount(n); }
static int wrap_getDevice(uint32_t i, ANeuralNetworksDevice** d) { return g_adapter.getDevice(i, d); }
static int wrap_Device_getName(const ANeuralNetworksDevice* d, const char** n) { return g_adapter.Device_getName(d, n); }
static int wrap_Device_getType(const ANeuralNetworksDevice* d, int32_t* t) { return g_adapter.Device_getType(d, t); }
static int wrap_Device_wait(const ANeuralNetworksDevice* d) { return g_adapter.Device_wait(d); }
static int wrap_Model_create(ANeuralNetworksModel** m) { return g_adapter.Model_create(m); }
static void wrap_Model_free(ANeuralNetworksModel* m) { g_adapter.Model_free(m); }
static int wrap_Model_addOperand(ANeuralNetworksModel* m, const ANeuralNetworksOperandType* t) { return g_adapter.Model_addOperand(m, t); }
static int wrap_Model_setOperandValue(ANeuralNetworksModel* m, int32_t i, const void* b, size_t l) { return g_adapter.Model_setOperandValue(m, i, b, l); }
static int wrap_Model_identifyInputsAndOutputs(ANeuralNetworksModel* m, uint32_t ic, const uint32_t* i, uint32_t oc, const uint32_t* o) { return g_adapter.Model_identifyInputsAndOutputs(m, ic, i, oc, o); }
static int wrap_Model_finish(ANeuralNetworksModel* m) { return g_adapter.Model_finish(m); }
static int wrap_Memory_createFromFd(size_t s, int p, int fd, size_t o, ANeuralNetworksMemory** m) { return g_adapter.Memory_createFromFd(s, p, fd, o, m); }
static int wrap_Compilation_createForDevices(ANeuralNetworksModel* m, const ANeuralNetworksDevice* const* d, uint32_t n, ANeuralNetworksCompilation** c) {
    LOGI("[APU] Compilation_createForDevices: model=%p numDevices=%u", (void*)m, n);
    return g_adapter.Compilation_createForDevices(m, d, n, c);
}
static void wrap_Compilation_free(ANeuralNetworksCompilation* c) { g_adapter.Compilation_free(c); }
static int wrap_Compilation_finish(ANeuralNetworksCompilation* c) { return g_adapter.Compilation_finish(c); }
static int wrap_Execution_create(ANeuralNetworksCompilation* c, ANeuralNetworksExecution** e) { return g_adapter.Execution_create(c, e); }
static void wrap_Execution_free(ANeuralNetworksExecution* e) { g_adapter.Execution_free(e); }
static int wrap_Execution_setInput(ANeuralNetworksExecution* e, int32_t i, const ANeuralNetworksOperandType* t, const void* b, size_t l) { return g_adapter.Execution_setInput(e, i, t, b, l); }
static int wrap_Execution_setOutput(ANeuralNetworksExecution* e, int32_t i, const ANeuralNetworksOperandType* t, void* b, size_t l) { return g_adapter.Execution_setOutput(e, i, t, b, l); }
static int wrap_Execution_compute(ANeuralNetworksExecution* e) {
    LOGI("[APU] Execution_compute: %p", (void*)e);
    int ret = g_adapter.Execution_compute(e);
    LOGI("[APU] Execution_compute returned: %d", ret);
    return ret;
}
static int wrap_Execution_startCompute(ANeuralNetworksExecution* e, ANeuralNetworksEvent** ev) { return g_adapter.Execution_startCompute(e, ev); }
static int wrap_Event_wait(ANeuralNetworksEvent* e) { return g_adapter.Event_wait(e); }
static void wrap_Event_free(ANeuralNetworksEvent* e) { g_adapter.Event_free(e); }

static void populateNnApiSL() {
    if (g_nnapi_sl_populated) return;
    memset(&g_nnapi_sl, 0, sizeof(g_nnapi_sl));
    g_nnapi_sl.base.implFeatureLevel = ANEURALNETWORKS_FEATURE_LEVEL_5;

    g_nnapi_sl.ANeuralNetworks_getDeviceCount = wrap_getDeviceCount;
    g_nnapi_sl.ANeuralNetworks_getDevice = wrap_getDevice;
    g_nnapi_sl.ANeuralNetworksDevice_getName = wrap_Device_getName;
    g_nnapi_sl.ANeuralNetworksDevice_getType = wrap_Device_getType;
    g_nnapi_sl.ANeuralNetworksDevice_wait = wrap_Device_wait;
    g_nnapi_sl.ANeuralNetworksModel_create = wrap_Model_create;
    g_nnapi_sl.ANeuralNetworksModel_free = wrap_Model_free;
    g_nnapi_sl.ANeuralNetworksModel_addOperand = wrap_Model_addOperand;
    g_nnapi_sl.ANeuralNetworksModel_setOperandValue = wrap_Model_setOperandValue;
    g_nnapi_sl.ANeuralNetworksModel_identifyInputsAndOutputs = wrap_Model_identifyInputsAndOutputs;
    g_nnapi_sl.ANeuralNetworksModel_finish = wrap_Model_finish;
    g_nnapi_sl.ANeuralNetworksMemory_createFromFd = wrap_Memory_createFromFd;
    g_nnapi_sl.ANeuralNetworksCompilation_createForDevices = wrap_Compilation_createForDevices;
    g_nnapi_sl.ANeuralNetworksCompilation_free = wrap_Compilation_free;
    g_nnapi_sl.ANeuralNetworksCompilation_finish = wrap_Compilation_finish;
    g_nnapi_sl.ANeuralNetworksExecution_create = wrap_Execution_create;
    g_nnapi_sl.ANeuralNetworksExecution_free = wrap_Execution_free;
    g_nnapi_sl.ANeuralNetworksExecution_setInput = wrap_Execution_setInput;
    g_nnapi_sl.ANeuralNetworksExecution_setOutput = wrap_Execution_setOutput;
    g_nnapi_sl.ANeuralNetworksExecution_compute = wrap_Execution_compute;
    g_nnapi_sl.ANeuralNetworksExecution_startCompute = wrap_Execution_startCompute;
    g_nnapi_sl.ANeuralNetworksEvent_wait = wrap_Event_wait;
    g_nnapi_sl.ANeuralNetworksEvent_free = wrap_Event_free;

    g_nnapi_sl_populated = true;
    LOGI("[APU] NnApiSL shim populated ✓ (FL5, %zu bytes)", sizeof(g_nnapi_sl));
}

// ═══════════════════════════════════════════════════════════════════════════
// JNI Methods
// ═══════════════════════════════════════════════════════════════════════════

extern "C" JNIEXPORT jlong JNICALL
Java_com_aetheria_vance_brain_NeuronBridge_nativeInit(
    JNIEnv* env, jobject /*thiz*/,
    jstring modelPath, jstring cacheDir)
{
    const char* mp = env->GetStringUTFChars(modelPath, nullptr);
    const char* cd = env->GetStringUTFChars(cacheDir, nullptr);
    std::string model_path(mp);
    std::string cache_dir(cd);
    env->ReleaseStringUTFChars(modelPath, mp);
    env->ReleaseStringUTFChars(cacheDir, cd);

    std::lock_guard<std::mutex> lock(g_mutex);

    NpuSession* s = new (std::nothrow) NpuSession();
    if (!s) { LOGE("OOM"); return 0L; }
    s->model_path = model_path;

    // 1. Load TFLite C API
    if (!loadTFLite()) {
        LOGE("[APU] Cannot load TFLite C API");
        delete s;
        return 0L;
    }

    // 2. Load NNAPI adapter
    s->adapter_handle = loadAdapter();
    if (!s->adapter_handle) {
        LOGE("[APU] Cannot load adapter");
        delete s;
        return 0L;
    }

    // 3. Resolve adapter symbols
    if (!resolveAdapter(s->adapter_handle)) {
        LOGE("[APU] Cannot resolve adapter symbols");
        dlclose(s->adapter_handle);
        delete s;
        return 0L;
    }
    LOGI("[APU] Adapter symbols resolved ✓");

    // 4. Populate NNAPI SL shim
    populateNnApiSL();

    // 5. Load TFLite model
    s->model = pfn_TfLiteModelCreateFromFile(model_path.c_str());
    if (!s->model) {
        LOGE("[APU] TfLiteModelCreateFromFile failed: %s", model_path.c_str());
        dlclose(s->adapter_handle);
        delete s;
        return 0L;
    }
    LOGI("[APU] TFLite model loaded: %s ✓", model_path.c_str());

    // 6. Create interpreter options
    s->interp_opts = pfn_TfLiteInterpreterOptionsCreate();
    if (!s->interp_opts) {
        LOGE("[APU] TfLiteInterpreterOptionsCreate failed");
        pfn_TfLiteModelDelete(s->model);
        dlclose(s->adapter_handle);
        delete s;
        return 0L;
    }

    // 7. Inject NNAPI SL shim via delegate params
    TfLiteNnApiDelegateParams* delegateParams = pfn_TfLiteNnApiDelegateParamsCreate();
    if (delegateParams) {
        pfn_TfLiteNnApiDelegateParamsSetSupportLibraryHandle(delegateParams, &g_nnapi_sl);
        LOGI("[APU] NnApiDelegateParams: SL shim injected ✓");

        TfLiteDelegate* nnapiDelegate = pfn_TfLiteNnApiDelegateCreate(delegateParams);
        if (nnapiDelegate) {
            pfn_TfLiteInterpreterOptionsAddDelegate(s->interp_opts, nnapiDelegate);
            LOGI("[APU] NnApiDelegate injected with SL shim ✓");
        } else {
            LOGE("[APU] TfLiteNnApiDelegateCreate failed — falling back to CPU");
        }
        pfn_TfLiteNnApiDelegateParamsDelete(delegateParams);
    } else {
        LOGE("[APU] TfLiteNnApiDelegateParamsCreate failed — falling back to CPU");
    }

    // 8. Create interpreter
    s->interpreter = pfn_TfLiteInterpreterCreate(s->model, s->interp_opts);
    if (!s->interpreter) {
        LOGE("[APU] TfLiteInterpreterCreate failed");
        pfn_TfLiteInterpreterOptionsDelete(s->interp_opts);
        pfn_TfLiteModelDelete(s->model);
        dlclose(s->adapter_handle);
        delete s;
        return 0L;
    }

    // 9. Allocate tensors
    if (pfn_TfLiteInterpreterAllocateTensors(s->interpreter) != kTfLiteOk) {
        LOGE("[APU] AllocateTensors failed");
        pfn_TfLiteInterpreterDelete(s->interpreter);
        pfn_TfLiteInterpreterOptionsDelete(s->interp_opts);
        pfn_TfLiteModelDelete(s->model);
        dlclose(s->adapter_handle);
        delete s;
        return 0L;
    }

    int ic = pfn_TfLiteInterpreterGetInputTensorCount(s->interpreter);
    int oc = pfn_TfLiteInterpreterGetOutputTensorCount(s->interpreter);
    LOGI("[APU] Tensors: input_count=%d output_count=%d ✓", ic, oc);

    if (ic < 1 || oc < 1) {
        LOGE("[APU] Model has no input/output tensors");
        pfn_TfLiteInterpreterDelete(s->interpreter);
        pfn_TfLiteInterpreterOptionsDelete(s->interp_opts);
        pfn_TfLiteModelDelete(s->model);
        dlclose(s->adapter_handle);
        delete s;
        return 0L;
    }

    LOGI("[APU] Session ready: %p ✓", (void*)s);
    return (jlong)s;
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_aetheria_vance_brain_NeuronBridge_nativeInfer(
    JNIEnv* env, jobject /*thiz*/,
    jlong handle, jstring prompt)
{
    const char* prompt_str = env->GetStringUTFChars(prompt, nullptr);
    std::string input(prompt_str);
    env->ReleaseStringUTFChars(prompt, prompt_str);

    NpuSession* s = (NpuSession*)handle;
    if (!s || !s->interpreter) {
        return env->NewStringUTF("APU_ERROR: invalid session");
    }

    TfLiteTensor* input_tensor = pfn_TfLiteInterpreterGetInputTensor(s->interpreter, 0);
    if (!input_tensor) {
        return env->NewStringUTF("APU_ERROR: input tensor");
    }

    size_t copyLen = input.size();
    size_t tensorBytes = pfn_TfLiteTensorByteSize(input_tensor);
    if (copyLen > tensorBytes) copyLen = tensorBytes;
    memcpy(pfn_TfLiteTensorData(input_tensor), input.data(), copyLen);

    TfLiteStatus status = pfn_TfLiteInterpreterInvoke(s->interpreter);
    if (status != kTfLiteOk) {
        LOGE("[APU] InterpreterInvoke failed: %d", (int)status);
        return env->NewStringUTF("APU_ERROR: invoke failed");
    }

    const TfLiteTensor* output_tensor = pfn_TfLiteInterpreterGetOutputTensor(s->interpreter, 0);
    if (!output_tensor) {
        return env->NewStringUTF("APU_ERROR: output tensor");
    }

    const void* outData = pfn_TfLiteTensorData(output_tensor);
    size_t outBytes = pfn_TfLiteTensorByteSize(output_tensor);
    TfLiteType outType = pfn_TfLiteTensorType(output_tensor);
    std::string result;

    if (outType == kTfLiteInt32 && outBytes >= sizeof(int32_t)) {
        int32_t* tokens = (int32_t*)outData;
        size_t count = outBytes / sizeof(int32_t);
        if (count >= 4) {
            char buf[256];
            snprintf(buf, sizeof(buf), "[%d, %d, %d, %d]",
                     tokens[0], tokens[1], tokens[2], tokens[3]);
            result = buf;
        } else {
            result = "APU_ERROR: output too short";
        }
    } else if (outType == kTfLiteFloat32 && outBytes >= sizeof(float)) {
        float* values = (float*)outData;
        size_t count = outBytes / sizeof(float);
        if (count >= 4) {
            char buf[256];
            snprintf(buf, sizeof(buf), "[%.1f, %.1f, %.1f, %.1f]",
                     values[0], values[1], values[2], values[3]);
            result = buf;
        } else {
            result = "APU_ERROR: output too short";
        }
    } else {
        char buf[256];
        snprintf(buf, sizeof(buf), "APU_OUTPUT: type=%d bytes=%zu",
                 (int)outType, outBytes);
        result = buf;
    }

    LOGI("[APU] Smoke test result: %s", result.c_str());
    return env->NewStringUTF(result.c_str());
}

extern "C" JNIEXPORT void JNICALL
Java_com_aetheria_vance_brain_NeuronBridge_nativeClose(
    JNIEnv* /*env*/, jobject /*thiz*/, jlong handle)
{
    NpuSession* s = (NpuSession*)handle;
    if (!s) return;

    std::lock_guard<std::mutex> lock(g_mutex);

    if (s->interpreter) { pfn_TfLiteInterpreterDelete(s->interpreter); s->interpreter = nullptr; }
    if (s->interp_opts) { pfn_TfLiteInterpreterOptionsDelete(s->interp_opts); s->interp_opts = nullptr; }
    if (s->model) { pfn_TfLiteModelDelete(s->model); s->model = nullptr; }
    if (s->adapter_handle) { dlclose(s->adapter_handle); s->adapter_handle = nullptr; }

    LOGI("[APU] Session closed: %p", (void*)s);
    delete s;
}
