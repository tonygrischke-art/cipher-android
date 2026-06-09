# ============================================================================
# CMakeLists.txt — NPU Native Build (Phase 2)
# File: app/src/main/cpp/CMakeLists.txt
#
# Phase 2: TFLite C API + NNAPI SL Shim
#   - neuron_bridge.cpp uses TFLite C API to load .tflite models
#   - Injects NnApiSLDriverImplFL5 shim into TFLite's NnApiDelegate
#   - Shim redirects NNAPI calls to libneuron_adapter_mgvi.so (MediaTek APU)
#
# TFLite headers: app/src/main/cpp/tflite_headers/
#   Copy these 5 files from the TF 2.16.1 source tree:
#     tensorflow/lite/c/c_api.h
#     tensorflow/lite/c/c_api_experimental.h
#     tensorflow/lite/c/common.h
#     tensorflow/lite/delegates/nnapi/nnapi_delegate_c_api.h
#     tensorflow/lite/nnapi/sl/public/NeuralNetworksSupportLibrary.h
#
# NOTE: These are MINIMAL stubs with only the types/functions needed by
# neuron_bridge.cpp. If the real TFLite headers are available, replace them.
# ============================================================================

cmake_minimum_required(VERSION 3.18.1)
project("neuron_bridge")

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

find_library(log-lib log)

# ── TFLite headers (minimal stubs) ─────────────────────────────────────────
# In a real build, these would come from the TensorFlow source tree or
# from the tensorflow-lite AAR's headers. We ship minimal stubs that
# define the types and function signatures neuron_bridge.cpp needs.
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

# ── NPU loader stub (safe dlopen for libvance_npu.so) ──────────────────────
add_library(npu_loader SHARED npu_loader.cpp)

target_link_libraries(npu_loader
    android
    log
    dl
)
