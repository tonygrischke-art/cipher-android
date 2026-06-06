# ============================================================================
# CMakeLists.txt — NPU Native Build
# File: app/src/main/cpp/CMakeLists.txt
# ============================================================================

cmake_minimum_required(VERSION 3.18.1)
project("vance_npu")

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

find_library(log-lib log)

# ── Neuron SDK adapter .so (bundled in APK lib/arm64-v8a) ─────────────────
add_library(neuron_adapter SHARED IMPORTED)
set_target_properties(neuron_adapter PROPERTIES
    IMPORTED_LOCATION ${CMAKE_SOURCE_DIR}/../jniLibs/arm64-v8a/libneuronusdk_adapter.mtk.so
)

add_library(neuron_allocator SHARED IMPORTED)
set_target_properties(neuron_allocator PROPERTIES
    IMPORTED_LOCATION ${CMAKE_SOURCE_DIR}/../jniLibs/arm64-v8a/libneuron_buffer_allocator.so
)

# ── NeuronBridge JNI library ───────────────────────────────────────────────
# neuron_bridge.cpp dlopen's libneuronusdk_adapter.mtk.so at runtime.
# We do NOT link it at build time — the IMPORTED target above is a
# handle so CMake knows about it, but the actual symbol resolution
# happens via dlsym() in the code.
add_library(
    neuron_bridge
    SHARED
    neuron_bridge.cpp
)

target_include_directories(neuron_bridge PRIVATE ${CMAKE_SOURCE_DIR})

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
