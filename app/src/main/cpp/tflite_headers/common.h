// tensorflow/lite/c/common.h (TFLite 2.16.1)
// Minimal subset for neuron_bridge.cpp Phase 2
#ifndef TENSORFLOW_LITE_C_COMMON_H_
#define TENSORFLOW_LITE_C_COMMON_H_

#include <stdint.h>
#include <stdlib.h>
#include <stdbool.h>

#ifdef __cplusplus
extern "C" {
#endif

// TfLiteQuantizationParams (used in some delegate APIs)
typedef struct TfLiteQuantizationParams {
    float scale;
    int32_t zero_point;
} TfLiteQuantizationParams;

#ifdef __cplusplus
}
#endif

#endif  // TENSORFLOW_LITE_C_COMMON_H_
