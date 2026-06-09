// tensorflow/lite/nnapi/sl/public/NeuralNetworksSupportLibrary.h (TFLite 2.16.1)
//
// Defines NnApiSLDriverImplFL5 and NnApiSLDriverImplFL4 — the Support Library
// shim structs that TFLite's NnApiDelegate uses when
// nnapi_support_library_handle is set.
//
// Each field is a function pointer matching the ANeuralNetworks* API.
// We fill these with pointers into libneuron_adapter_mgvi.so so TFLite
// calls the MediaTek vendor driver instead of system NNAPI.
//
// Source: tensorflow/lite/nnapi/sl/public/NeuralNetworksSupportLibrary.h
//         in the TensorFlow 2.16.1 source tree.
#ifndef TENSORFLOW_LITE_NNAPI_SL_PUBLIC_NEURALNETWORKSSUPPORTLIBRARY_H_
#define TENSORFLOW_LITE_NNAPI_SL_PUBLIC_NEURALNETWORKSSUPPORTLIBRARY_H_

#include <stdint.h>
#include <stddef.h>

#ifdef __cplusplus
extern "C" {
#endif

// ── NNAPI Feature Levels ──
#define ANEURALNETWORKS_FEATURE_LEVEL_1  27
#define ANEURALNETWORKS_FEATURE_LEVEL_2  28
#define ANEURALNETWORKS_FEATURE_LEVEL_3  29
#define ANEURALNETWORKS_FEATURE_LEVEL_4  30
#define ANEURALNETWORKS_FEATURE_LEVEL_5  31

// ── Forward declarations of NNAPI opaque types ──
typedef struct ANeuralNetworksModel ANeuralNetworksModel;
typedef struct ANeuralNetworksCompilation ANeuralNetworksCompilation;
typedef struct ANeuralNetworksExecution ANeuralNetworksExecution;
typedef struct ANeuralNetworksDevice ANeuralNetworksDevice;
typedef struct ANeuralNetworksMemory ANeuralNetworksMemory;
typedef struct ANeuralNetworksEvent ANeuralNetworksEvent;
typedef struct ANeuralNetworksOperandType ANeuralNetworksOperandType;

// ── Base struct (common to all feature levels) ──
typedef struct NnApiSLDriverImplBase {
    int32_t implFeatureLevel;  // ANEURALNETWORKS_FEATURE_LEVEL_*
    // The runtime may fill additional standard fields after this.
    // Size is checked at runtime — if our struct is too small, the
    // runtime reports a version mismatch.
    void* nnapiSLItoa;  // reserved
} NnApiSLDriverImplBase;

// ── NnApiSLDriverImplFL4 (Android 11 / NNAPI Feature Level 4) ──
// Use this if FL5 causes a size mismatch at runtime.
typedef struct NnApiSLDriverImplFL4 {
    NnApiSLDriverImplBase base;

    // Devices
    int (*ANeuralNetworks_getDeviceCount)(uint32_t* numDevices);
    int (*ANeuralNetworks_getDevice)(uint32_t devIndex,
        ANeuralNetworksDevice** device);
    int (*ANeuralNetworksDevice_getName)(const ANeuralNetworksDevice* device,
        const char** name);
    int (*ANeuralNetworksDevice_getType)(const ANeuralNetworksDevice* device,
        int32_t* type);
    int (*ANeuralNetworksDevice_wait)(const ANeuralNetworksDevice* device);

    // Model
    int (*ANeuralNetworksModel_create)(ANeuralNetworksModel** model);
    void (*ANeuralNetworksModel_free)(ANeuralNetworksModel* model);
    int (*ANeuralNetworksModel_addOperand)(ANeuralNetworksModel* model,
        const ANeuralNetworksOperandType* type);
    int (*ANeuralNetworksModel_setOperandValue)(ANeuralNetworksModel* model,
        int32_t index, const void* buffer, size_t length);
    int (*ANeuralNetworksModel_identifyInputsAndOutputs)(
        ANeuralNetworksModel* model,
        uint32_t inputCount, const uint32_t* inputs,
        uint32_t outputCount, const uint32_t* outputs);
    int (*ANeuralNetworksModel_finish)(ANeuralNetworksModel* model);

    // Memory
    int (*ANeuralNetworksMemory_createFromFd)(size_t size, int protect,
        int fd, size_t offset, ANeuralNetworksMemory** memory);

    // Compilation
    int (*ANeuralNetworksCompilation_createForDevices)(
        ANeuralNetworksModel* model,
        const ANeuralNetworksDevice* const* devices,
        uint32_t numDevices,
        ANeuralNetworksCompilation** compilation);
    void (*ANeuralNetworksCompilation_free)(
        ANeuralNetworksCompilation* compilation);
    int (*ANeuralNetworksCompilation_finish)(
        ANeuralNetworksCompilation* compilation);

    // Execution
    int (*ANeuralNetworksExecution_create)(
        ANeuralNetworksCompilation* compilation,
        ANeuralNetworksExecution** execution);
    void (*ANeuralNetworksExecution_free)(
        ANeuralNetworksExecution* execution);
    int (*ANeuralNetworksExecution_setInput)(
        ANeuralNetworksExecution* execution,
        int32_t index, const ANeuralNetworksOperandType* type,
        const void* buffer, size_t length);
    int (*ANeuralNetworksExecution_setOutput)(
        ANeuralNetworksExecution* execution,
        int32_t index, const ANeuralNetworksOperandType* type,
        void* buffer, size_t length);
    int (*ANeuralNetworksExecution_compute)(
        ANeuralNetworksExecution* execution);
    int (*ANeuralNetworksExecution_startCompute)(
        ANeuralNetworksExecution* execution,
        ANeuralNetworksEvent** event);

    // Event
    int (*ANeuralNetworksEvent_wait)(ANeuralNetworksEvent* event);
    void (*ANeuralNetworksEvent_free)(ANeuralNetworksEvent* event);

    // FL4 extensions (if any) would go here
} NnApiSLDriverImplFL4;

// ── NnApiSLDriverImplFL5 (Android 12+ / NNAPI Feature Level 5) ──
// Extends FL4 with additional function pointers for newer NNAPI features.
// If runtime reports size mismatch, fall back to FL4.
typedef struct NnApiSLDriverImplFL5 {
    NnApiSLDriverImplBase base;

    // Devices
    int (*ANeuralNetworks_getDeviceCount)(uint32_t* numDevices);
    int (*ANeuralNetworks_getDevice)(uint32_t devIndex,
        ANeuralNetworksDevice** device);
    int (*ANeuralNetworksDevice_getName)(const ANeuralNetworksDevice* device,
        const char** name);
    int (*ANeuralNetworksDevice_getType)(const ANeuralNetworksDevice* device,
        int32_t* type);
    int (*ANeuralNetworksDevice_wait)(const ANeuralNetworksDevice* device);

    // Model
    int (*ANeuralNetworksModel_create)(ANeuralNetworksModel** model);
    void (*ANeuralNetworksModel_free)(ANeuralNetworksModel* model);
    int (*ANeuralNetworksModel_addOperand)(ANeuralNetworksModel* model,
        const ANeuralNetworksOperandType* type);
    int (*ANeuralNetworksModel_setOperandValue)(ANeuralNetworksModel* model,
        int32_t index, const void* buffer, size_t length);
    int (*ANeuralNetworksModel_identifyInputsAndOutputs)(
        ANeuralNetworksModel* model,
        uint32_t inputCount, const uint32_t* inputs,
        uint32_t outputCount, const uint32_t* outputs);
    int (*ANeuralNetworksModel_finish)(ANeuralNetworksModel* model);

    // Memory
    int (*ANeuralNetworksMemory_createFromFd)(size_t size, int protect,
        int fd, size_t offset, ANeuralNetworksMemory** memory);

    // Compilation
    int (*ANeuralNetworksCompilation_createForDevices)(
        ANeuralNetworksModel* model,
        const ANeuralNetworksDevice* const* devices,
        uint32_t numDevices,
        ANeuralNetworksCompilation** compilation);
    void (*ANeuralNetworksCompilation_free)(
        ANeuralNetworksCompilation* compilation);
    int (*ANeuralNetworksCompilation_finish)(
        ANeuralNetworksCompilation* compilation);

    // Execution
    int (*ANeuralNetworksExecution_create)(
        ANeuralNetworksCompilation* compilation,
        ANeuralNetworksExecution** execution);
    void (*ANeuralNetworksExecution_free)(
        ANeuralNetworksExecution* execution);
    int (*ANeuralNetworksExecution_setInput)(
        ANeuralNetworksExecution* execution,
        int32_t index, const ANeuralNetworksOperandType* type,
        const void* buffer, size_t length);
    int (*ANeuralNetworksExecution_setOutput)(
        ANeuralNetworksExecution* execution,
        int32_t index, const ANeuralNetworksOperandType* type,
        void* buffer, size_t length);
    int (*ANeuralNetworksExecution_compute)(
        ANeuralNetworksExecution* execution);
    int (*ANeuralNetworksExecution_startCompute)(
        ANeuralNetworksExecution* execution,
        ANeuralNetworksEvent** event);

    // Event
    int (*ANeuralNetworksEvent_wait)(ANeuralNetworksEvent* event);
    void (*ANeuralNetworksEvent_free)(ANeuralNetworksEvent* event);

    // FL5 extensions
    int (*ANeuralNetworksExecution_setReusable)(
        ANeuralNetworksExecution* execution, bool reusable);
    int (*ANeuralNetworksExecution_setLoopTimeout)(
        ANeuralNetworksExecution* execution, uint64_t duration);
    int (*ANeuralNetworksExecution_setMeasureTiming)(
        ANeuralNetworksExecution* execution, bool measure);
    int (*ANeuralNetworksExecution_getDuration)(
        const ANeuralNetworksExecution* execution,
        int32_t durationCode, uint64_t* duration);
} NnApiSLDriverImplFL5;

#ifdef __cplusplus
}
#endif

#endif  // TENSORFLOW_LITE_NNAPI_SL_PUBLIC_NEURALNETWORKSSUPPORTLIBRARY_H_
