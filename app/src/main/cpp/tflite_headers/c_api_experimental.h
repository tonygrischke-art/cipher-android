// tensorflow/lite/c/c_api_experimental.h (TFLite 2.16.1)
// Minimal subset for neuron_bridge.cpp Phase 2
#ifndef TENSORFLOW_LITE_C_C_API_EXPERIMENTAL_H_
#define TENSORFLOW_LITE_C_C_API_EXPERIMENTAL_H_

#include "c_api.h"

#ifndef TFL_CAPI_EXPORT
#define TFL_CAPI_EXPORT __attribute__((visibility("default")))
#endif

#ifdef __cplusplus
extern "C" {
#endif

// Registration for custom ops/delegates would go here.
// Phase 2 doesn't use these yet — placeholder for future.

#ifdef __cplusplus
}
#endif

#endif  // TENSORFLOW_LITE_C_C_API_EXPERIMENTAL_H_
