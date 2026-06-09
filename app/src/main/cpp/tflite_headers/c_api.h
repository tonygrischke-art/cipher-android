// tensorflow/lite/c/c_api.h (TFLite 2.16.1)
// Minimal subset needed for neuron_bridge.cpp Phase 2
#ifndef TENSORFLOW_LITE_C_C_API_H_
#define TENSORFLOW_LITE_C_C_API_H_

#include <stdint.h>
#include <stdlib.h>

#ifndef TFL_CAPI_EXPORT
#if defined(_WIN32)
#ifdef TFL_COMPILE_LIBRARY
#define TFL_CAPI_EXPORT __declspec(dllexport)
#else
#define TFL_CAPI_EXPORT __declspec(dllimport)
#endif
#else
#define TFL_CAPI_EXPORT __attribute__((visibility("default")))
#endif
#endif

#ifdef __cplusplus
extern "C" {
#endif

// Opaque types
typedef struct TfLiteModel TfLiteModel;
typedef struct TfLiteInterpreter TfLiteInterpreter;
typedef struct TfLiteInterpreterOptions TfLiteInterpreterOptions;
typedef struct TfLiteDelegate TfLiteDelegate;
typedef struct TfLiteDevice TfLiteDevice;
typedef struct TfLiteTensor TfLiteTensor;

// Status enum
typedef enum TfLiteStatus {
    kTfLiteOk = 0,
    kTfLiteError = 1,
    kTfLiteDelegateError = 2,
    kTfLiteApplicationError = 3
} TfLiteStatus;

// Type enum
typedef enum TfLiteType {
    kTfLiteNoType = 0,
    kTfLiteFloat32 = 1,
    kTfLiteInt32 = 2,
    kTfLiteUInt8 = 3,
    kTfLiteInt64 = 4,
    kTfLiteString = 5,
    kTfLiteBool = 6,
    kTfLiteInt16 = 7,
    kTfLiteComplex64 = 8,
    kTfLiteInt8 = 9,
    kTfLiteFloat16 = 10,
    kTfLiteFloat64 = 11,
    kTfLiteComplex128 = 12,
    kTfLiteUInt64 = 13,
    kTfLiteResource = 14,
    kTfLiteVariant = 15,
    kTfLiteUInt32 = 16,
    kTfLiteUInt16 = 17,
    kTfLiteInt4 = 18,
} TfLiteType;

// ── Model ──
TFL_CAPI_EXPORT extern TfLiteModel* TfLiteModelCreateFromFile(const char* model_path);
TFL_CAPI_EXPORT extern void TfLiteModelDelete(TfLiteModel* model);

// ── Interpreter Options ──
TFL_CAPI_EXPORT extern TfLiteInterpreterOptions* TfLiteInterpreterOptionsCreate(void);
TFL_CAPI_EXPORT extern void TfLiteInterpreterOptionsDelete(TfLiteInterpreterOptions* options);
TFL_CAPI_EXPORT extern void TfLiteInterpreterOptionsAddDelegate(
    TfLiteInterpreterOptions* options, TfLiteDelegate* delegate);

// ── Interpreter ──
TFL_CAPI_EXPORT extern TfLiteInterpreter* TfLiteInterpreterCreate(
    TfLiteModel* model, const TfLiteInterpreterOptions* optional_options);
TFL_CAPI_EXPORT extern void TfLiteInterpreterDelete(TfLiteInterpreter* interpreter);
TFL_CAPI_EXPORT extern TfLiteStatus TfLiteInterpreterAllocateTensors(
    TfLiteInterpreter* interpreter);
TFL_CAPI_EXPORT extern int32_t TfLiteInterpreterGetInputTensorCount(
    const TfLiteInterpreter* interpreter);
TFL_CAPI_EXPORT extern int32_t TfLiteInterpreterGetOutputTensorCount(
    const TfLiteInterpreter* interpreter);
TFL_CAPI_EXPORT extern TfLiteTensor* TfLiteInterpreterGetInputTensor(
    const TfLiteInterpreter* interpreter, int32_t input_index);
TFL_CAPI_EXPORT extern const TfLiteTensor* TfLiteInterpreterGetOutputTensor(
    const TfLiteInterpreter* interpreter, int32_t output_index);
TFL_CAPI_EXPORT extern TfLiteStatus TfLiteInterpreterInvoke(
    TfLiteInterpreter* interpreter);

// ── Tensor (opaque, access via functions) ──
TFL_CAPI_EXPORT extern TfLiteType TfLiteTensorType(const TfLiteTensor* tensor);
TFL_CAPI_EXPORT extern size_t TfLiteTensorByteSize(const TfLiteTensor* tensor);
TFL_CAPI_EXPORT extern void* TfLiteTensorData(const TfLiteTensor* tensor);

#ifdef __cplusplus
}
#endif

#endif  // TENSORFLOW_LITE_C_C_API_H_
