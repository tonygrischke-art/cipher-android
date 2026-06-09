// tensorflow/lite/delegates/nnapi/nnapi_delegate_c_api.h (TFLite 2.16.1)
// Minimal subset for neuron_bridge.cpp Phase 2
//
// This header exposes the C API for TFLite's NNAPI delegate, including
// the nnapi_support_library_handle injection point that lets us redirect
// NNAPI calls through our shim into libneuron_adapter_mgvi.so.
#ifndef TENSORFLOW_LITE_DELEGATES_NNAPI_NNAPI_DELEGATE_C_API_H_
#define TENSORFLOW_LITE_DELEGATES_NNAPI_NNAPI_DELEGATE_C_API_H_

#include "c_api.h"

#ifndef TFL_CAPI_EXPORT
#define TFL_CAPI_EXPORT __attribute__((visibility("default")))
#endif

#ifdef __cplusplus
extern "C" {
#endif

// Opaque NNAPI delegate params
typedef struct TfLiteNnApiDelegateParams TfLiteNnApiDelegateParams;

// ── NNAPI Delegate Params ──
// In TFLite 2.16.1, TfLiteNnApiDelegateParams is an opaque struct.
// The nnapi_support_library_handle field (if present) accepts a pointer
// to an NnApiSLDriverImplFL5 struct that replaces system NNAPI.
//
// NOTE: The actual TFLite 2.16.1 C API may not expose
// nnapi_support_library_handle directly. If the build fails with
// "unknown member 'nnapi_support_library_handle'", the field may only
// be available in the C++ API (TfLiteNnApiDelegateOptions).
// In that case, neuron_bridge.cpp needs to use the C++ API instead,
// or patch the TFLite source to expose it in the C API.

// Create/destroy delegate params
TFL_CAPI_EXPORT extern TfLiteNnApiDelegateParams* TfLiteNnApiDelegateParamsCreate(void);
TFL_CAPI_EXPORT extern void TfLiteNnApiDelegateParamsDelete(
    TfLiteNnApiDelegateParams* params);

// Set the NNAPI support library handle (our injection point)
// This field tells the NNAPI delegate to use our shim instead of
// dlopen'ing the system libneuralnetworks.so
TFL_CAPI_EXPORT extern void TfLiteNnApiDelegateParamsSetSupportLibraryHandle(
    TfLiteNnApiDelegateParams* params, void* sl_handle);

// ── NNAPI Delegate ──
TFL_CAPI_EXPORT extern TfLiteDelegate* TfLiteNnApiDelegateCreate(
    const TfLiteNnApiDelegateParams* params);
TFL_CAPI_EXPORT extern void TfLiteNnApiDelegateDelete(TfLiteDelegate* delegate);

// Convenience: create with default params (no SL injection)
TFL_CAPI_EXPORT extern TfLiteDelegate* TfLiteNnApiDelegateCreateDefault(void);

#ifdef __cplusplus
}
#endif

#endif  // TENSORFLOW_LITE_DELEGATES_NNAPI_NNAPI_DELEGATE_C_API_H_
