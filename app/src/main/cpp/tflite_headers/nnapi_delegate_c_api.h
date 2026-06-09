// tensorflow/lite/delegates/nnapi/nnapi_delegate_c_api.h (TFLite 2.16.1)
// CORRECTED: matches actual symbols exported by libtensorflowlite_jni.so
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
// Opaque options struct (actual size ~24 bytes for TF 2.16.1).
// Use TfLiteNnapiDelegateOptionsDefault() to initialize.
// Then pass to TfLiteNnapiDelegateCreate().
typedef struct {
    char _opaque[32];  // opaque storage — must be zero-init'd before TfLiteNnapiDelegateOptionsDefault
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
