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
// The injection point is via TfLiteNnApiDelegateParamsSetSupportLibraryHandle().
// This sets nnapi_support_library_handle in the delegate params, which tells
// TFLite's NnApiDelegate to use our g_nnapi_sl struct instead of system NNAPI.
// ============================================================================

#include <jni.h>
#include <dlfcn.h>
#include <android/log.h>
#include <string>
#include <vector>
#include <mutex>
#include <cstring>

// ── TFLite C API headers ──────────────────────────────────────────────────
#include "tflite_headers/c_api.h"
#include "tflite_headers/c_api_experimental.h"
#include "tflite_headers/common.h"
#include "tflite_headers/nnapi_delegate_c_api.h"
#include "tflite_headers/NeuralNetworksSupportLibrary.h"

#define LOG_TAG "APU"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO,  LOG_TAG, __VA_ARGS__)
#define LOGD(...) __android_log_print(ANDROID_LOG_DEBUG, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

// ── NNAPI SL shim struct ─────────────────────────────────────────────────
// Try FL5 first (Android 12+). If populateNnApiSL() hits a size/version
// mismatch at runtime, switch to NnApiSLDriverImplFL4 (Android 11).
static NnApiSLDriverImplFL5 g_nnapi_sl{};
static bool g_nnapi_sl_populated = false;

// ── Function pointer types for libneuron_adapter_mgvi.so ─────────────────
typedef int (*PFN_ANeuralNetworksMemory_createFromFd)(
    size_t size, int protect, int fd, size_t offset,
    ANeuralNetworksMemory** memory);

typedef int (*PFN_ANeuralNetworksCompilation_createForDevices)(
    ANeuralNetworksModel* model,
    const ANeuralNetworksDevice* const* devices,
    uint32_t numDevices,
    ANeuralNetworksCompilation** compilation);

typedef int (*PFN_ANeuralNetworksCompilation_finish)(
    ANeuralNetworksCompilation* compilation);

typedef int (*PFN_ANeuralNetworksExecution_create)(
    ANeuralNetworksCompilation* compilation,
    ANeuralNetworksExecution** execution);

typedef int (*PFN_ANeuralNetworksExecution_setInput)(
    ANeuralNetworksExecution* execution,
    int32_t index,
    const ANeuralNetworksOperandType* type,
    const void* buffer,
    size_t length);

typedef int (*PFN_ANeuralNetworksExecution_setOutput)(
    ANeuralNetworksExecution* execution,
    int32_t index,
    const ANeuralNetworksOperandType* type,
    void* buffer,
    size_t length);

typedef int (*PFN_ANeuralNetworksExecution_compute)(
    ANeuralNetworksExecution* execution);

typedef int (*PFN_ANeuralNetworksExecution_startCompute)(
    ANeuralNetworksExecution* execution,
    ANeuralNetworksEvent** event);

typedef int (*PFN_ANeuralNetworksExecution_setReusable)(
    ANeuralNetworksExecution* execution, bool reusable);

typedef int (*PFN_ANeuralNetworksModel_create)(
    ANeuralNetworksModel** model);

typedef int (*PFN_ANeuralNetworksModel_addOperand)(
    ANeuralNetworksModel* model,
    const ANeuralNetworksOperandType* type);

typedef int (*PFN_ANeuralNetworksModel_setOperandValue)(
    ANeuralNetworksModel* model,
    int32_t index,
    const void* buffer,
    size_t length);

typedef int (*PFN_ANeuralNetworksModel_identifyInputsAndOutputs)(
    ANeuralNetworksModel* model,
    uint32_t inputCount,
    const uint32_t* inputs,
    uint32_t outputCount,
    const uint32_t* outputs);

typedef int (*PFN_ANeuralNetworksModel_finish)(
    ANeuralNetworksModel* model);

typedef void (*PFN_ANeuralNetworksModel_free)(
    ANeuralNetworksModel* model);

typedef void (*PFN_ANeuralNetworksCompilation_free)(
    ANeuralNetworksCompilation* compilation);

typedef void (*PFN_ANeuralNetworksExecution_free)(
    ANeuralNetworksExecution* execution);

typedef int (*PFN_ANeuralNetworksDevice_getName)(
    const ANeuralNetworksDevice* device,
    const char** name);

typedef int (*PFN_ANeuralNetworksDevice_getType)(
    const ANeuralNetworksDevice* device,
    int32_t* type);

typedef int (*PFN_ANeuralNetworksDevice_wait)(
    const ANeuralNetworksDevice* device);

typedef int (*PFN_ANeuralNetworks_getDeviceCount)(
    uint32_t* numDevices);

typedef int (*PFN_ANeuralNetworks_getDevice)(
    uint32_t devIndex,
    ANeuralNetworksDevice** device);

typedef int (*PFN_ANeuralNetworksEvent_wait)(
    ANeuralNetworksEvent* event);

typedef void (*PFN_ANeuralNetworksEvent_free)(
    ANeuralNetworksEvent* event);

// ── Shim function pointers loaded from libneuron_adapter_mgvi.so ─────────
struct NnApiShims {
    PFN_ANeuralNetworksMemory_createFromFd Memory_createFromFd = nullptr;
    PFN_ANeuralNetworksCompilation_createForDevices Compilation_createForDevices = nullptr;
    PFN_ANeuralNetworksCompilation_finish Compilation_finish = nullptr;
    PFN_ANeuralNetworksExecution_create Execution_create = nullptr;
    PFN_ANeuralNetworksExecution_setInput Execution_setInput = nullptr;
    PFN_ANeuralNetworksExecution_setOutput Execution_setOutput = nullptr;
    PFN_ANeuralNetworksExecution_compute Execution_compute = nullptr;
    PFN_ANeuralNetworksExecution_startCompute Execution_startCompute = nullptr;
    PFN_ANeuralNetworksExecution_setReusable Execution_setReusable = nullptr;
    PFN_ANeuralNetworksModel_create Model_create = nullptr;
    PFN_ANeuralNetworksModel_addOperand Model_addOperand = nullptr;
    PFN_ANeuralNetworksModel_setOperandValue Model_setOperandValue = nullptr;
    PFN_ANeuralNetworksModel_identifyInputsAndOutputs Model_identifyInputsAndOutputs = nullptr;
    PFN_ANeuralNetworksModel_finish Model_finish = nullptr;
    PFN_ANeuralNetworksModel_free Model_free = nullptr;
    PFN_ANeuralNetworksCompilation_free Compilation_free = nullptr;
    PFN_ANeuralNetworksExecution_free Execution_free = nullptr;
    PFN_ANeuralNetworksDevice_getName Device_getName = nullptr;
    PFN_ANeuralNetworksDevice_getType Device_getType = nullptr;
    PFN_ANeuralNetworksDevice_wait Device_wait = nullptr;
    PFN_ANeuralNetworks_getDeviceCount getDeviceCount = nullptr;
    PFN_ANeuralNetworks_getDevice getDevice = nullptr;
    PFN_ANeuralNetworksEvent_wait Event_wait = nullptr;
    PFN_ANeuralNetworksEvent_free Event_free = nullptr;
};

static NnApiShims g_adapter;

// ── Session state ─────────────────────────────────────────────────────────
struct NpuSession {
    void* adapter_handle = nullptr;       // dlopen handle for libneuron_adapter_mgvi.so
    TfLiteModel* model = nullptr;         // TFLite model handle
    TfLiteInterpreter* interpreter = nullptr;
    TfLiteInterpreterOptions* interp_opts = nullptr;

    std::string model_path;
};

static std::mutex g_mutex;

// ── Load adapter library ──────────────────────────────────────────────────
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
        if (h) { LOGI("Loaded adapter from: %s", p); return h; }
    }
    LOGE("Cannot load libneuron_adapter_mgvi.so: %s", dlerror());
    return nullptr;
}

// ── Resolve one symbol ────────────────────────────────────────────────────
template<typename T>
static bool resolveSym(void* h, const char* name, T& out) {
    out = (T)dlsym(h, name);
    if (!out) { LOGE("dlsym(%s) failed: %s", name, dlerror()); return false; }
    return true;
}

// ── Resolve all adapter symbols ───────────────────────────────────────────
static bool resolveAdapter(void* h) {
    bool ok = true;
    ok &= resolveSym(h, "ANeuralNetworksMemory_createFromFd", g_adapter.Memory_createFromFd);
    ok &= resolveSym(h, "ANeuralNetworksCompilation_createForDevices", g_adapter.Compilation_createForDevices);
    ok &= resolveSym(h, "ANeuralNetworksCompilation_finish", g_adapter.Compilation_finish);
    ok &= resolveSym(h, "ANeuralNetworksExecution_create", g_adapter.Execution_create);
    ok &= resolveSym(h, "ANeuralNetworksExecution_setInput", g_adapter.Execution_setInput);
    ok &= resolveSym(h, "ANeuralNetworksExecution_setOutput", g_adapter.Execution_setOutput);
    ok &= resolveSym(h, "ANeuralNetworksExecution_compute", g_adapter.Execution_compute);
    ok &= resolveSym(h, "ANeuralNetworksExecution_startCompute", g_adapter.Execution_startCompute);
    ok &= resolveSym(h, "ANeuralNetworksModel_create", g_adapter.Model_create);
    ok &= resolveSym(h, "ANeuralNetworksModel_addOperand", g_adapter.Model_addOperand);
    ok &= resolveSym(h, "ANeuralNetworksModel_setOperandValue", g_adapter.Model_setOperandValue);
    ok &= resolveSym(h, "ANeuralNetworksModel_identifyInputsAndOutputs", g_adapter.Model_identifyInputsAndOutputs);
    ok &= resolveSym(h, "ANeuralNetworksModel_finish", g_adapter.Model_finish);
    ok &= resolveSym(h, "ANeuralNetworksModel_free", g_adapter.Model_free);
    ok &= resolveSym(h, "ANeuralNetworksCompilation_free", g_adapter.Compilation_free);
    ok &= resolveSym(h, "ANeuralNetworksExecution_free", g_adapter.Execution_free);
    ok &= resolveSym(h, "ANeuralNetworksDevice_getName", g_adapter.Device_getName);
    ok &= resolveSym(h, "ANeuralNetworksDevice_getType", g_adapter.Device_getType);
    ok &= resolveSym(h, "ANeuralNetworksDevice_wait", g_adapter.Device_wait);
    ok &= resolveSym(h, "ANeuralNetworks_getDeviceCount", g_adapter.getDeviceCount);
    ok &= resolveSym(h, "ANeuralNetworks_getDevice", g_adapter.getDevice);
    ok &= resolveSym(h, "ANeuralNetworksEvent_wait", g_adapter.Event_wait);
    ok &= resolveSym(h, "ANeuralNetworksEvent_free", g_adapter.Event_free);
    // FL5 optional symbol
    resolveSym(h, "ANeuralNetworksExecution_setReusable", g_adapter.Execution_setReusable);

    if (!ok) LOGE("Failed to resolve some adapter symbols");
    return ok;
}

// ── Wrapper functions that our shim calls through to the adapter ─────────
// These are the actual function pointers placed in g_nnapi_sl.
// They forward to g_adapter which was populated via dlsym from libneuron_adapter_mgvi.so.

static int shim_Memory_createFromFd(size_t size, int protect, int fd, size_t offset,
    ANeuralNetworksMemory** memory) {
    return g_adapter.Memory_createFromFd(size, protect, fd, offset, memory);
}

static int shim_Compilation_createForDevices(ANeuralNetworksModel* model,
    const ANeuralNetworksDevice* const* devices, uint32_t numDevices,
    ANeuralNetworksCompilation** compilation) {
    LOGI("[APU] Compilation_createForDevices: model=%p numDevices=%u", (void*)model, numDevices);
    return g_adapter.Compilation_createForDevices(model, devices, numDevices, compilation);
}

static int shim_Compilation_finish(ANeuralNetworksCompilation* compilation) {
    return g_adapter.Compilation_finish(compilation);
}

static int shim_Execution_create(ANeuralNetworksCompilation* compilation,
    ANeuralNetworksExecution** execution) {
    return g_adapter.Execution_create(compilation, execution);
}

static int shim_Execution_setInput(ANeuralNetworksExecution* execution, int32_t index,
    const ANeuralNetworksOperandType* type, const void* buffer, size_t length) {
    return g_adapter.Execution_setInput(execution, index, type, buffer, length);
}

static int shim_Execution_setOutput(ANeuralNetworksExecution* execution, int32_t index,
    const ANeuralNetworksOperandType* type, void* buffer, size_t length) {
    return g_adapter.Execution_setOutput(execution, index, type, buffer, length);
}

static int shim_Execution_compute(ANeuralNetworksExecution* execution) {
    LOGI("[APU] Execution_compute: %p", (void*)execution);
    int ret = g_adapter.Execution_compute(execution);
    LOGI("[APU] Execution_compute returned: %d", ret);
    return ret;
}

static int shim_Execution_startCompute(ANeuralNetworksExecution* execution,
    ANeuralNetworksEvent** event) {
    return g_adapter.Execution_startCompute(execution, event);
}

static int shim_Model_create(ANeuralNetworksModel** model) {
    return g_adapter.Model_create(model);
}

static int shim_Model_addOperand(ANeuralNetworksModel* model,
    const ANeuralNetworksOperandType* type) {
    return g_adapter.Model_addOperand(model, type);
}

static int shim_Model_setOperandValue(ANeuralNetworksModel* model, int32_t index,
    const void* buffer, size_t length) {
    return g_adapter.Model_setOperandValue(model, index, buffer, length);
}

static int shim_Model_identifyInputsAndOutputs(ANeuralNetworksModel* model,
    uint32_t inputCount, const uint32_t* inputs, uint32_t outputCount, const uint32_t* outputs) {
    return g_adapter.Model_identifyInputsAndOutputs(model, inputCount, inputs, outputCount, outputs);
}

static int shim_Model_finish(ANeuralNetworksModel* model) {
    return g_adapter.Model_finish(model);
}

static void shim_Model_free(ANeuralNetworksModel* model) {
    g_adapter.Model_free(model);
}

static void shim_Compilation_free(ANeuralNetworksCompilation* compilation) {
    g_adapter.Compilation_free(compilation);
}

static void shim_Execution_free(ANeuralNetworksExecution* execution) {
    g_adapter.Execution_free(execution);
}

static int shim_Device_getName(const ANeuralNetworksDevice* device, const char** name) {
    return g_adapter.Device_getName(device, name);
}

static int shim_Device_getType(const ANeuralNetworksDevice* device, int32_t* type) {
    return g_adapter.Device_getType(device, type);
}

static int shim_Device_wait(const ANeuralNetworksDevice* device) {
    return g_adapter.Device_wait(device);
}

static int shim_getDeviceCount(uint32_t* numDevices) {
    return g_adapter.getDeviceCount(numDevices);
}

static int shim_getDevice(uint32_t devIndex, ANeuralNetworksDevice** device) {
    return g_adapter.getDevice(devIndex, device);
}

static int shim_Event_wait(ANeuralNetworksEvent* event) {
    return g_adapter.Event_wait(event);
}

static void shim_Event_free(ANeuralNetworksEvent* event) {
    g_adapter.Event_free(event);
}

// ── Populate NNAPI SL shim struct ────────────────────────────────────────
static void populateNnApiSL() {
    if (g_nnapi_sl_populated) return;

    memset(&g_nnapi_sl, 0, sizeof(g_nnapi_sl));

    // FL5 = Android 12+. If runtime reports size mismatch, switch to FL4.
    g_nnapi_sl.base.implFeatureLevel = ANEURALNETWORKS_FEATURE_LEVEL_5;

    // Fill all function pointers with our shim wrappers
    g_nnapi_sl.ANeuralNetworks_getDeviceCount = shim_getDeviceCount;
    g_nnapi_sl.ANeuralNetworks_getDevice = shim_getDevice;
    g_nnapi_sl.ANeuralNetworksDevice_getName = shim_Device_getName;
    g_nnapi_sl.ANeuralNetworksDevice_getType = shim_Device_getType;
    g_nnapi_sl.ANeuralNetworksDevice_wait = shim_Device_wait;

    g_nnapi_sl.ANeuralNetworksModel_create = shim_Model_create;
    g_nnapi_sl.ANeuralNetworksModel_free = shim_Model_free;
    g_nnapi_sl.ANeuralNetworksModel_addOperand = shim_Model_addOperand;
    g_nnapi_sl.ANeuralNetworksModel_setOperandValue = shim_Model_setOperandValue;
    g_nnapi_sl.ANeuralNetworksModel_identifyInputsAndOutputs = shim_Model_identifyInputsAndOutputs;
    g_nnapi_sl.ANeuralNetworksModel_finish = shim_Model_finish;

    g_nnapi_sl.ANeuralNetworksMemory_createFromFd = shim_Memory_createFromFd;

    g_nnapi_sl.ANeuralNetworksCompilation_createForDevices = shim_Compilation_createForDevices;
    g_nnapi_sl.ANeuralNetworksCompilation_free = shim_Compilation_free;
    g_nnapi_sl.ANeuralNetworksCompilation_finish = shim_Compilation_finish;

    g_nnapi_sl.ANeuralNetworksExecution_create = shim_Execution_create;
    g_nnapi_sl.ANeuralNetworksExecution_free = shim_Execution_free;
    g_nnapi_sl.ANeuralNetworksExecution_setInput = shim_Execution_setInput;
    g_nnapi_sl.ANeuralNetworksExecution_setOutput = shim_Execution_setOutput;
    g_nnapi_sl.ANeuralNetworksExecution_compute = shim_Execution_compute;
    g_nnapi_sl.ANeuralNetworksExecution_startCompute = shim_Execution_startCompute;

    // FL5 extension
    if (g_adapter.Execution_setReusable) {
        g_nnapi_sl.ANeuralNetworksExecution_setReusable = [](
            ANeuralNetworksExecution* e, bool r) -> int {
            return g_adapter.Execution_setReusable ? g_adapter.Execution_setReusable(e, r) : 0;
        };
    }

    g_nnapi_sl.ANeuralNetworksEvent_wait = shim_Event_wait;
    g_nnapi_sl.ANeuralNetworksEvent_free = shim_Event_free;

    g_nnapi_sl_populated = true;
    LOGI("[APU] NnApiSL shim populated ✓ (FL5, %zu bytes)", sizeof(g_nnapi_sl));
}

// ── Find mtk-neuron device ────────────────────────────────────────────────
static bool findMtkNeuronDevice(ANeuralNetworksDevice** outDevice, int32_t* outType) {
    uint32_t numDevices = 0;
    if (g_adapter.getDeviceCount(&numDevices) != 0) {
        LOGE("[APU] getDeviceCount failed");
        return false;
    }
    LOGI("[APU] Found %u NNAPI device(s)", numDevices);

    for (uint32_t i = 0; i < numDevices; i++) {
        ANeuralNetworksDevice* device = nullptr;
        if (g_adapter.getDevice(i, &device) != 0 || !device) continue;

        const char* name = nullptr;
        g_adapter.Device_getName(device, &name);
        int32_t type = 0;
        g_adapter.Device_getType(device, &type);

        LOGI("[APU] Device %u: name=%s type=%d", i, name ? name : "null", type);

        if (name && strstr(name, "mtk") != nullptr) {
            LOGI("[APU] Target device selected: %s ✓", name);
            *outDevice = device;
            *outType = type;
            return true;
        }
    }

    LOGE("[APU] No mtk-neuron device found");
    return false;
}

// ============================================================================
// JNI Methods
// ============================================================================

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

    // 1. Load adapter
    s->adapter_handle = loadAdapter();
    if (!s->adapter_handle) { LOGE("Cannot load adapter"); delete s; return 0L; }

    // 2. Resolve adapter symbols
    if (!resolveAdapter(s->adapter_handle)) {
        LOGE("Cannot resolve adapter symbols");
        dlclose(s->adapter_handle);
        delete s;
        return 0L;
    }
    LOGI("[APU] Adapter symbols resolved ✓");

    // 3. Populate NNAPI SL shim
    populateNnApiSL();

    // 4. Load TFLite model
    s->model = TfLiteModelCreateFromFile(model_path.c_str());
    if (!s->model) {
        LOGE("[APU] TfLiteModelCreateFromFile failed: %s", model_path.c_str());
        dlclose(s->adapter_handle);
        delete s;
        return 0L;
    }
    LOGI("[APU] TFLite model loaded: %s ✓", model_path.c_str());

    // 5. Create interpreter options
    s->interp_opts = TfLiteInterpreterOptionsCreate();
    if (!s->interp_opts) {
        LOGE("[APU] TfLiteInterpreterOptionsCreate failed");
        TfLiteModelDelete(s->model);
        dlclose(s->adapter_handle);
        delete s;
        return 0L;
    }

    // 6. Inject NNAPI SL shim via delegate params
    // This is the key: TfLiteNnApiDelegateParamsSetSupportLibraryHandle()
    // tells TFLite's NnApiDelegate to call through g_nnapi_sl instead of
    // the system libneuralnetworks.so
    TfLiteNnApiDelegateParams* delegateParams = TfLiteNnApiDelegateParamsCreate();
    if (delegateParams) {
        TfLiteNnApiDelegateParamsSetSupportLibraryHandle(delegateParams, &g_nnapi_sl);
        LOGI("[APU] NnApiDelegateParams: SL shim injected ✓");

        TfLiteDelegate* nnapiDelegate = TfLiteNnApiDelegateCreate(delegateParams);
        if (nnapiDelegate) {
            TfLiteInterpreterOptionsAddDelegate(s->interp_opts, nnapiDelegate);
            LOGI("[APU] NnApiDelegate injected with SL shim ✓");
        } else {
            LOGE("[APU] TfLiteNnApiDelegateCreate failed — falling back to CPU");
        }
        TfLiteNnApiDelegateParamsDelete(delegateParams);
    } else {
        LOGE("[APU] TfLiteNnApiDelegateParamsCreate failed — falling back to CPU");
    }

    // 7. Create interpreter
    s->interpreter = TfLiteInterpreterCreate(s->model, s->interp_opts);
    if (!s->interpreter) {
        LOGE("[APU] TfLiteInterpreterCreate failed");
        TfLiteInterpreterOptionsDelete(s->interp_opts);
        TfLiteModelDelete(s->model);
        dlclose(s->adapter_handle);
        delete s;
        return 0L;
    }

    // 8. Allocate tensors
    if (TfLiteInterpreterAllocateTensors(s->interpreter) != kTfLiteOk) {
        LOGE("[APU] AllocateTensors failed");
        TfLiteInterpreterDelete(s->interpreter);
        TfLiteInterpreterOptionsDelete(s->interp_opts);
        TfLiteModelDelete(s->model);
        dlclose(s->adapter_handle);
        delete s;
        return 0L;
    }

    // 9. Log tensor info
    int input_tensor_count = TfLiteInterpreterGetInputTensorCount(s->interpreter);
    int output_tensor_count = TfLiteInterpreterGetOutputTensorCount(s->interpreter);
    LOGI("[APU] Tensors: input_count=%d output_count=%d ✓", input_tensor_count, output_tensor_count);

    if (input_tensor_count < 1 || output_tensor_count < 1) {
        LOGE("[APU] Model has no input/output tensors");
        TfLiteInterpreterDelete(s->interpreter);
        TfLiteInterpreterOptionsDelete(s->interp_opts);
        TfLiteModelDelete(s->model);
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

    LOGD("[APU] Inferencing: input_len=%zu", input.size());

    // ── Get input tensor ──
    TfLiteTensor* input_tensor = TfLiteInterpreterGetInputTensor(s->interpreter, 0);
    if (!input_tensor) {
        LOGE("[APU] Cannot get input tensor");
        return env->NewStringUTF("APU_ERROR: input tensor");
    }

    // ── Copy input data ──
    size_t copyLen = input.size();
    size_t tensorBytes = TfLiteTensorByteSize(input_tensor);
    if (copyLen > tensorBytes) copyLen = tensorBytes;
    memcpy(TfLiteTensorData(input_tensor), input.data(), copyLen);

    // ── Invoke ──
    TfLiteStatus status = TfLiteInterpreterInvoke(s->interpreter);
    if (status != kTfLiteOk) {
        LOGE("[APU] InterpreterInvoke failed: %d", (int)status);
        return env->NewStringUTF("APU_ERROR: invoke failed");
    }

    // ── Read output tensor ──
    const TfLiteTensor* output_tensor = TfLiteInterpreterGetOutputTensor(s->interpreter, 0);
    if (!output_tensor) {
        LOGE("[APU] Cannot get output tensor");
        return env->NewStringUTF("APU_ERROR: output tensor");
    }

    // ── Format output ──
    const void* outData = TfLiteTensorData(output_tensor);
    size_t outBytes = TfLiteTensorByteSize(output_tensor);
    TfLiteType outType = TfLiteTensorType(output_tensor);
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

    if (s->interpreter) {
        TfLiteInterpreterDelete(s->interpreter);
        s->interpreter = nullptr;
    }
    if (s->interp_opts) {
        TfLiteInterpreterOptionsDelete(s->interp_opts);
        s->interp_opts = nullptr;
    }
    if (s->model) {
        TfLiteModelDelete(s->model);
        s->model = nullptr;
    }
    if (s->adapter_handle) {
        dlclose(s->adapter_handle);
        s->adapter_handle = nullptr;
    }

    LOGI("[APU] Session closed: %p", (void*)s);
    delete s;
}
