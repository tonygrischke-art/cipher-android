# ============================================================================
# CMakeLists.txt — NPU Native Build (Standard NNAPI Delegate)
# File: app/src/main/cpp/CMakeLists.txt
#
# Standard NNAPI delegate path:
#   - neuron_bridge.cpp uses TfLiteNnapiDelegateCreate() with named options
#     (accelerator_name="mtk-neuron_shim", disallow_nnapi_cpu=1)
#   - No NnApiSL shim injection needed
#   - System NNAPI APEX routes to MTK vendor driver automatically
#
# TFLite headers: app/src/main/cpp/tflite_headers/
#   Copy from TF 2.16.1 source tree:
#     tensorflow/lite/c/c_api.h
#     tensorflow/lite/c/c_api_experimental.h
#     tensorflow/lite/c/common.h
#     tensorflow/lite/delegates/nnapi/nnapi_delegate_c_api.h
#   (NeuralNetworksSupportLibrary.h no longer needed)
# ============================================================================

cmake_minimum_required(VERSION 3.18.1)
project("neuron_bridge")

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

find_library(log-lib log)

# ── TFLite headers ─────────────────────────────────────────────────────────
set(TFLITE_HEADERS_DIR ${CMAKE_SOURCE_DIR}/tflite_headers)

# ── NeuronBridge JNI library ───────────────────────────────────────────────
add_library(
    neuron_bridge
    SHARED
    neuron_bridge.cpp
)

target_include_directories(neuron_bridge PRIVATE
    ${CMAKE_SOURCE_DIR}
    ${TFLITE_HEADERS_DIR}
)

target_link_libraries(
    neuron_bridge
    ${log-lib}
    dl
    android
)

target_compile_options(neuron_bridge PRIVATE
    -O3
    -ffunction-sections
    -fdata-sections
    -fvisibility=hidden
)

target_link_options(neuron_bridge PRIVATE
    -Wl,--gc-sections
    -Wl,-s
)

# ── NPU loader stub ────────────────────────────────────────────────────────
add_library(npu_loader SHARED npu_loader.cpp)

target_link_libraries(npu_loader
    android
    log
    dl
)

# ── WakeNPUEngine — TFLite wake word classifier via NNAPI ──────────────────
add_library(
    wake_npu_engine
    SHARED
    wake_npu_engine.cpp
)

target_include_directories(wake_npu_engine PRIVATE
    ${CMAKE_SOURCE_DIR}
    ${TFLITE_HEADERS_DIR}
)

target_link_libraries(wake_npu_engine
    ${log-lib}
    dl
    android
)

target_compile_options(wake_npu_engine PRIVATE
    -O3
    -ffunction-sections
    -fdata-sections
    -fvisibility=hidden
)

target_link_options(wake_npu_engine PRIVATE
    -Wl,--gc-sections
    -Wl,-s
)

# ── VanceNpuEngine — Direct TFLite C API + NNAPI delegate ──────────────────
add_library(
    vance_npu_engine
    SHARED
    vance_npu_engine.cpp
)

target_include_directories(vance_npu_engine PRIVATE
    ${CMAKE_SOURCE_DIR}
    ${TFLITE_HEADERS_DIR}
)

target_link_libraries(vance_npu_engine
    ${log-lib}
    dl
    android
)

target_compile_options(vance_npu_engine PRIVATE
    -O3
    -ffunction-sections
    -fdata-sections
    -fvisibility=hidden
)

target_link_options(vance_npu_engine PRIVATE
    -Wl,--gc-sections
    -Wl,-s
)

# ── TFLite shared library (bundled in jniLibs from AAR) ─────────────────
add_library(
    tensorflowlite
    SHARED
    IMPORTED
)
set_target_properties(
    tensorflowlite
    PROPERTIES
    IMPORTED_LOCATION ${CMAKE_SOURCE_DIR}/../jniLibs/${ANDROID_ABI}/libtensorflowlite_jni.so
)
target_link_libraries(vance_npu_engine tensorflowlite)
