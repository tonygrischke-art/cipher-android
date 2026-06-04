# ============================================================================
# CMakeLists.txt — NPU Native Build
# File: app/src/main/cpp/CMakeLists.txt
# ============================================================================

cmake_minimum_required(VERSION 3.18.1)
project("vance_npu")

# Set C++ standard
set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

# Find Android log library
find_library(log-lib log)

# Build the NPU bridge shared library
add_library(
    vance_npu
    SHARED
    neuron_bridge.cpp
)

# Link libraries
target_link_libraries(
    vance_npu
    ${log-lib}
    dl  # Dynamic loading for libneuron_adapter_mgvi.so
)

# Compiler flags for optimization
target_compile_options(vance_npu PRIVATE
    -O3
    -ffunction-sections
    -fdata-sections
    -fvisibility=hidden
)

# Linker flags
target_link_options(vance_npu PRIVATE
    -Wl,--gc-sections
    -Wl,-s
)

# Thin loader stub — no Neuron dependencies, always safe to dlopen.
add_library(npu_loader SHARED npu_loader.cpp)

target_link_libraries(npu_loader
    android
    log
    dl          # required for dlopen / dlsym / dlclose
)
