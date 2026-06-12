#include <jni.h>
#include <android/log.h>

#define TAG "NpuLoader"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, TAG, __VA_ARGS__)

// Stub loader — the real work is in libneuron_bridge.so
// This library exists so that older code paths that load "npu_loader" don't crash.
// All NPU functionality is now in neuron_bridge (standard NNAPI delegate path).

extern "C" {

JNIEXPORT jint JNICALL JNI_OnLoad(JavaVM* vm, void* reserved) {
    LOGI("npu_loader stub loaded — NPU functionality is in libneuron_bridge.so");
    return JNI_VERSION_1_6;
}

}  // extern "C"
