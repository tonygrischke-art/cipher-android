// Minimal stubs for TFLite C API types used via dlsym at runtime.
// Avoids the broken 2.16.1 AAR header tree (missing core/async/c/types.h).
// Only the types/structs we actually touch are declared here.

#ifndef TFLITE_STUBS_H
#define TFLITE_STUBS_H

#include <cstdint>
#include <cstddef>

#ifdef __cplusplus
extern "C" {
#endif

// ── Opaque handle types (pointers only, never dereferenced) ──────────────
typedef struct TfLiteModel                TfLiteModel;
typedef struct TfLiteInterpreterOptions   TfLiteInterpreterOptions;
typedef struct TfLiteInterpreter          TfLiteInterpreter;
typedef struct TfLiteDelegate             TfLiteDelegate;
typedef struct TfLiteTensor               TfLiteTensor;

// ── Enums ────────────────────────────────────────────────────────────────
typedef enum TfLiteStatus { kTfLiteOk = 0, kTfLiteError = 1 } TfLiteStatus;

typedef enum TfLiteType {
    kTfLiteNoType = 0, kTfLiteFloat32 = 1, kTfLiteInt32 = 2,
    kTfLiteUInt8 = 3, kTfLiteInt64 = 4, kTfLiteString = 5,
    kTfLiteBool = 6, kTfLiteInt16 = 7, kTfLiteComplex64 = 8,
    kTfLiteInt8 = 9, kTfLiteFloat16 = 10, kTfLiteFloat64 = 11,
    kTfLiteComplex128 = 12, kTfLiteUInt64 = 13, kTfLiteUInt32 = 14,
    kTfLiteUInt16 = 15
} TfLiteType;

// ── NNAPI delegate options (real struct from AAR header) ────────────────
struct TfLiteNnapiDelegateOptions {
    enum ExecutionPreference {
        kUndefined = -1,
        kLowPower = 0,
        kFastSingleAnswer = 1,
        kSustainedSpeed = 2,
    };
    ExecutionPreference execution_preference;
    const char*        accelerator_name;
    const char*        cache_dir;
    const char*        model_token;
    int                disallow_nnapi_cpu;
    int                allow_fp16;
    int                max_number_delegated_partitions;
    void*              nnapi_support_library_handle;
};

#ifdef __cplusplus
}  // extern "C"
#endif

#endif // TFLITE_STUBS_H
