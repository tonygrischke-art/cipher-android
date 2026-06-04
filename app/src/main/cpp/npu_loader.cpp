#include <jni.h>
#include <dlfcn.h>
#include <android/log.h>

#define TAG "NpuLoader"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO,  TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, TAG, __VA_ARGS__)

// Global handle — nativeInit() is idempotent.
static void* g_vance_handle = nullptr;

extern "C" {

/**
 * Attempts to load libvance_npu.so lazily.
 *
 * RTLD_LAZY   — defers Neuron symbol resolution until first call site,
 *               so missing symbols don't crash during dlopen.
 * RTLD_GLOBAL — makes vance_npu symbols visible to the JVM's native
 *               method lookup so NpuBridge's other `external fun` methods
 *               resolve correctly after this returns true.
 *
 * Returns JNI_TRUE if library loaded and exports the expected JNI symbol.
 * Returns JNI_FALSE on any failure — no exceptions, no crashes.
 */
JNIEXPORT jboolean JNICALL
Java_com_aetheria_vance_brain_NpuBridge_nativeInit(JNIEnv* /*env*/,
                                                   jobject /*thiz*/) {
    if (g_vance_handle != nullptr) {
        return JNI_TRUE;  // idempotent
    }

    dlerror();  // clear stale error

    g_vance_handle = dlopen("libvance_npu.so", RTLD_LAZY | RTLD_GLOBAL);
    if (g_vance_handle == nullptr) {
        LOGE("dlopen(libvance_npu.so) failed: %s", dlerror());
        return JNI_FALSE;
    }

    // Probe the first JNI export from libvance_npu.so.
    // JNI method names are always exported even in stripped builds.
    void* probe = dlsym(g_vance_handle,
        "Java_com_aetheria_vance_brain_NpuBridge_nativeInitNpu");
    if (probe == nullptr) {
        LOGE("Symbol probe failed: %s", dlerror());
        dlclose(g_vance_handle);
        g_vance_handle = nullptr;
        return JNI_FALSE;
    }

    LOGI("libvance_npu.so loaded OK — NPU path available");
    return JNI_TRUE;
}

/**
 * Explicit cleanup. Call from NpuBridge.shutdownNpu() or Application.onTerminate().
 */
JNIEXPORT void JNICALL
Java_com_aetheria_vance_brain_NpuBridge_nativeRelease(JNIEnv* /*env*/,
                                                      jobject /*thiz*/) {
    if (g_vance_handle != nullptr) {
        dlclose(g_vance_handle);
        g_vance_handle = nullptr;
        LOGI("libvance_npu.so released");
    }
}

}  // extern "C"
