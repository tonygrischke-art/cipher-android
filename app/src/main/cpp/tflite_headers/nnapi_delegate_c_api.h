// tensorflow/lite/delegates/nnapi/nnapi_delegate_c_api.h (TFLite 2.16.1)
//
// Minimal NNAPI delegate C API — only the functions we need.
// The TfLiteNnapiDelegateOptions struct is opaque; we use
// TfLiteNnapiDelegateOptionsDefault() to initialize it, then
// set only the fields we need via the public API.
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
// Opaque struct. Use TfLiteNnapiDelegateOptionsDefault() to initialize.
// Size is ~48 bytes in TF 2.16.1.
typedef struct TfLiteNnapiDelegateOptions {
    char _opaque[64];  // generous padding for TF 2.16.1
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
