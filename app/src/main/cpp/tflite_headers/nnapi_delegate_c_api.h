// tensorflow/lite/delegates/nnapi/nnapi_delegate_c_api.h (TFLite 2.16.1)
//
// Standard NNAPI delegate options struct with named fields.
// This replaces the opaque stub — we can now set accelerator_name,
// disallow_nnapi_cpu, and execution_preference directly.
//
// Source: tensorflow/lite/delegates/nnapi/nnapi_delegate_c_api.h
//         in the TensorFlow 2.16.1 source tree.
#ifndef TENSORFLOW_LITE_DELEGATES_NNAPI_NNAPI_DELEGATE_C_API_H_
#define TENSORFLOW_LITE_DELEGATES_NNAPI_NNAPI_DELEGATE_C_API_H_

#include "c_api.h"

#ifndef TFL_CAPI_EXPORT
#define TFL_CAPI_EXPORT __attribute__((visibility("default")))
#endif

#ifdef __cplusplus
extern "C" {
#endif

// ── NNAPI Delegate Options ──
// Matches the actual TfLiteNnapiDelegateOptions struct layout in TF 2.16.1.
// Zero-init before calling TfLiteNnapiDelegateOptionsDefault().
typedef struct TfLiteNnapiDelegateOptions {
    // Opaque, reserved. Do not modify. Must be nullptr.
    void* _opaque;

    // Accelerator name to target (e.g. "mtk-neuron_shim", "mtk-mdla_shim").
    // If nullptr, the first available accelerator is used.
    const char* accelerator_name;

    // If non-zero, disallow the NNAPI CPU fallback delegate entirely.
    // Non-NPU operations will fail rather than silently run on CPU.
    int disallow_nnapi_cpu;

    // Execution preference for NNAPI compilation.
    // 0 = kUndefined (default)
    // 1 = kLowPower
    // 2 = kFastSingleAnswer
    // 3 = kHighPerformance / sustained speed
    int execution_preference;

    // Optional: cache directory for compiled model caching.
    // Set to nullptr to disable caching.
    const char* cache_dir;

    // Optional: model token for cache identification.
    const char* model_token;

    // Optional: max number of NNAPI partitions to delegate.
    // 0 = no limit (delegate everything possible).
    int max_delegated_partitions;

    // If non-zero, allow FP16 computation.
    int allow_fp16;

    // If non-zero, use NNAPI Burst mode.
    int use_burst_mode;

    // Reserved padding for future fields.
    char _reserved[8];
} TfLiteNnapiDelegateOptions;

// Initialize options to defaults
TFL_CAPI_EXPORT extern void TfLiteNnapiDelegateOptionsDefault(TfLiteNnapiDelegateOptions* options);

// Create/destroy NNAPI delegate from options
TFL_CAPI_EXPORT extern TfLiteDelegate* TfLiteNnapiDelegateCreate(const TfLiteNnapiDelegateOptions* options);
TFL_CAPI_EXPORT extern void TfLiteNnapiDelegateDelete(TfLiteDelegate* delegate);

#ifdef __cplusplus
}
#endif

#endif  // TENSORFLOW_LITE_DELEGATES_NNAPI_NNAPI_DELEGATE_C_API_H_
