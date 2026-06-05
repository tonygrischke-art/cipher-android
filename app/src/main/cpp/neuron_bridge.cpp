// ============================================================================
// NPU BRIDGE — JNI Interface to MediaTek Neuron Adapter
// File: app/src/main/cpp/neuron_bridge.cpp
// Target: libneuron_adapter_mgvi.so on MT6878 (Motorola)
// ============================================================================

#include <jni.h>
#include <dlfcn.h>
#include <android/log.h>
#include <string>
#include <vector>
#include <mutex>

#define LOG_TAG "VanceNPU"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define LOGD(...) __android_log_print(ANDROID_LOG_DEBUG, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

// ============================================================================
// Neuron Adapter Type Definitions
// These match the MediaTek Neuron SDK API
// ============================================================================

typedef int32_t NeuronModel;
typedef int32_t NeuronCompilation;
typedef int32_t NeuronExecution;
typedef int32_t NeuronMemory;

// Return codes
#define NEURON_NO_ERROR 0
#define NEURON_OUT_OF_MEMORY 1
#define NEURON_INCOMPLETE 2
#define NEURON_UNEXPECTED_NULL 3
struct NeuronModel;
struct NeuronCompilation;
struct NeuronExecution;
struct NeuronMemory;

// Return codes
#define NEURON_BAD_DATA 4
#define NEURON_OP_FAILED 5
#define NEURON_UNMAPPABLE 5
#define NEURON_BAD_STATE 6

// Neuron Adapter function signatures
typedef int (*PFN_NeuronModel_create)(NeuronModel** model);
typedef int (*PFN_NeuronModel_free)(NeuronModel* model);
typedef int (*PFN_NeuronModel_finish)(NeuronModel* model);
typedef int (*PFN_NeuronCompilation_create)(NeuronModel* model, NeuronCompilation** compilation);
typedef int (*PFN_NeuronCompilation_free)(NeuronCompilation* compilation);
typedef int (*PFN_NeuronCompilation_finish)(NeuronCompilation* compilation);
typedef int (*PFN_NeuronExecution_create)(NeuronCompilation* compilation, NeuronExecution** execution);
typedef int (*PFN_NeuronExecution_free)(NeuronExecution* execution);
typedef int (*PFN_NeuronExecution_setInput)(NeuronExecution* execution, int32_t index,
    const NeuronMemory* memory, const void* buffer, size_t length);
typedef int (*PFN_NeuronExecution_setOutput)(NeuronExecution* execution, int32_t index,
    const NeuronMemory* memory, void* buffer, size_t length);
typedef int (*PFN_NeuronExecution_compute)(NeuronExecution* execution);

// ============================================================================
// NPU Bridge State
// ============================================================================

class NpuBridge {
public:
    static NpuBridge& getInstance() {
        static NpuBridge instance;
        return instance;
    }

    bool initialize() {
        std::lock_guard<std::mutex> lock(mutex_);

        if (initialized_) {
            LOGI("NPU already initialized");
            return true;
        }

        // Try multiple paths for the Neuron adapter library
        const char* paths[] = {
            "/vendor/lib64/libneuron_adapter_mgvi.so",
            "/system/lib64/libneuron_adapter_mgvi.so",
            "/vendor/lib/libneuron_adapter_mgvi.so",
            "libneuron_adapter_mgvi.so"
        };

        for (const char* path : paths) {
            handle_ = dlopen(path, RTLD_LAZY);
            if (handle_) {
                LOGI("Loaded Neuron adapter from: %s", path);
                break;
            }
        }

        if (!handle_) {
            LOGE("Failed to load libneuron_adapter_mgvi.so: %s", dlerror());
            return false;
        }

        // Resolve all required symbols
        if (!resolveSymbols()) {
            LOGE("Failed to resolve Neuron symbols");
            dlclose(handle_);
            handle_ = nullptr;
            return false;
        }

        initialized_ = true;
        LOGI("NPU Bridge initialized — MediaTek APU ready");
        return true;
    }

    void shutdown() {
        std::lock_guard<std::mutex> lock(mutex_);
        if (handle_) {
            dlclose(handle_);
            handle_ = nullptr;
        }
        initialized_ = false;
        LOGI("NPU Bridge shutdown");
    }

    bool isAvailable() const {
        return initialized_ && handle_ != nullptr;
    }

    std::string getLastError() const {
        return lastError_;
    }

    // Run inference on a compiled model
    std::string runInference(const std::string& modelPath, const std::string& inputText) {
        if (!initialized_) {
            lastError_ = "NPU not initialized";
            return "NPU_NOT_INITIALIZED";
        }

        // TODO: Implement full inference pipeline
        // 1. Load TFLite model
        // 2. Create NeuronModel
        // 3. Compile with NeuronCompilation
        // 4. Create NeuronExecution
        // 5. Set input tensor
        // 6. Compute
        // 7. Read output tensor
        // 8. Decode tokens to text

        LOGD("Running NPU inference: model=%s, input_len=%zu", 
             modelPath.c_str(), inputText.length());

        // Placeholder — actual implementation requires TFLite integration
        return "NPU_INFERENCE_PLACEHOLDER";
    }

private:
    NpuBridge() : handle_(nullptr), initialized_(false) {}
    ~NpuBridge() { shutdown(); }

    bool resolveSymbols() {
        #define RESOLVE(name) \
            do { \
                name = (PFN_##name)dlsym(handle_, #name); \
                if (!name) { LOGE("Failed to resolve: %s", #name); return false; } \
            } while(0)

        RESOLVE(NeuronModel_create);
        RESOLVE(NeuronModel_free);
        RESOLVE(NeuronModel_finish);
        RESOLVE(NeuronCompilation_create);
        RESOLVE(NeuronCompilation_free);
        RESOLVE(NeuronCompilation_finish);
        RESOLVE(NeuronExecution_create);
        RESOLVE(NeuronExecution_free);
        RESOLVE(NeuronExecution_setInput);
        RESOLVE(NeuronExecution_setOutput);
        RESOLVE(NeuronExecution_compute);

        #undef RESOLVE
        return true;
    }

    void* handle_;
    bool initialized_;
    std::string lastError_;
    mutable std::mutex mutex_;

    // Function pointers
    PFN_NeuronModel_create NeuronModel_create = nullptr;
    PFN_NeuronModel_free NeuronModel_free = nullptr;
    PFN_NeuronModel_finish NeuronModel_finish = nullptr;
    PFN_NeuronCompilation_create NeuronCompilation_create = nullptr;
    PFN_NeuronCompilation_free NeuronCompilation_free = nullptr;
    PFN_NeuronCompilation_finish NeuronCompilation_finish = nullptr;
    PFN_NeuronExecution_create NeuronExecution_create = nullptr;
    PFN_NeuronExecution_free NeuronExecution_free = nullptr;
    PFN_NeuronExecution_setInput NeuronExecution_setInput = nullptr;
    PFN_NeuronExecution_setOutput NeuronExecution_setOutput = nullptr;
    PFN_NeuronExecution_compute NeuronExecution_compute = nullptr;
};

// ============================================================================
// JNI Exported Functions
// ============================================================================

extern "C" JNIEXPORT jboolean JNICALL
Java_com_aetheria_vance_brain_NpuBridge_nativeInitNpu(JNIEnv* env, jobject /*thiz*/) {
    return NpuBridge::getInstance().initialize() ? JNI_TRUE : JNI_FALSE;
}

extern "C" JNIEXPORT void JNICALL
Java_com_aetheria_vance_brain_NpuBridge_nativeShutdownNpu(JNIEnv* env, jobject /*thiz*/) {
    NpuBridge::getInstance().shutdown();
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_aetheria_vance_brain_NpuBridge_nativeIsNpuAvailable(JNIEnv* env, jobject /*thiz*/) {
    return NpuBridge::getInstance().isAvailable() ? JNI_TRUE : JNI_FALSE;
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_aetheria_vance_brain_NpuBridge_nativeRunInference(
    JNIEnv* env, jobject /*thiz*/,
    jstring modelPath,
    jstring inputText
) {
    const char* path = env->GetStringUTFChars(modelPath, nullptr);
    const char* input = env->GetStringUTFChars(inputText, nullptr);

    std::string result = NpuBridge::getInstance().runInference(path, input);

    env->ReleaseStringUTFChars(modelPath, path);
    env->ReleaseStringUTFChars(inputText, input);

    return env->NewStringUTF(result.c_str());
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_aetheria_vance_brain_NpuBridge_nativeGetLastError(JNIEnv* env, jobject /*thiz*/) {
    return env->NewStringUTF(NpuBridge::getInstance().getLastError().c_str());
}
