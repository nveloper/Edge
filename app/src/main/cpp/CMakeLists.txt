cmake_minimum_required(VERSION 3.22)
project(edge_llama C CXX)

set(CMAKE_C_STANDARD 11)
set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

set(THIRD_PARTY ${CMAKE_CURRENT_LIST_DIR}/../../../../third_party)
set(LLAMA_SRC ${THIRD_PARTY}/llama.cpp)

# Android 15+ devices may use 16 KB pages; every native library must be aligned for them.
add_link_options("-Wl,-z,max-page-size=16384")

# --- llama.cpp / ggml build options ----------------------------------------------------------
set(BUILD_SHARED_LIBS      ON  CACHE BOOL "" FORCE)
set(LLAMA_BUILD_COMMON     ON  CACHE BOOL "" FORCE) # chat templates (Jinja)
set(LLAMA_BUILD_TESTS      OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_TOOLS      OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_EXAMPLES   OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_SERVER     OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_APP        OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_MTMD       ON  CACHE BOOL "" FORCE) # image/audio input (libmtmd + mmproj)
set(LLAMA_OPENSSL          OFF CACHE BOOL "" FORCE)
set(LLAMA_SUBPROCESS       OFF CACHE BOOL "" FORCE)

set(GGML_NATIVE            OFF CACHE BOOL "" FORCE)
set(GGML_LLAMAFILE         OFF CACHE BOOL "" FORCE)
set(GGML_OPENMP            OFF CACHE BOOL "" FORCE)
# Each backend is its own .so, loaded at runtime: a device without OpenCL simply skips that
# backend instead of failing to load the app. CPU code is built for several ISA levels and the
# best one for the device is picked at runtime.
set(GGML_BACKEND_DL        ON  CACHE BOOL "" FORCE)
set(GGML_CPU_ALL_VARIANTS  ON  CACHE BOOL "" FORCE)

# --- Vulkan (main GPU backend) ---------------------------------------------------------------
# NDK ships the Vulkan loader (libvulkan.so) and C headers; ggml also needs the C++ headers.
set(GGML_VULKAN ON CACHE BOOL "" FORCE)
set(Vulkan_INCLUDE_DIR ${THIRD_PARTY}/Vulkan-Headers/include CACHE PATH "" FORCE)
# ggml's shaders need a recent glslc (cooperative-matrix syntax); the one bundled with the NDK
# is from 2022. Use third_party/shaderc (Google's shaderc release build) when present.
if (CMAKE_HOST_WIN32)
    set(EDGE_GLSLC ${THIRD_PARTY}/shaderc/bin/glslc.exe)
else()
    set(EDGE_GLSLC ${THIRD_PARTY}/shaderc/bin/glslc)
endif()
if (EXISTS ${EDGE_GLSLC})
    set(Vulkan_GLSLC_EXECUTABLE ${EDGE_GLSLC} CACHE FILEPATH "" FORCE)
else()
    message(WARNING "third_party/shaderc not found; falling back to the NDK's glslc, which may be too old")
    file(GLOB GLSLC_CANDIDATES "${ANDROID_NDK}/shader-tools/*/glslc*")
    list(GET GLSLC_CANDIDATES 0 GLSLC_FOUND)
    set(Vulkan_GLSLC_EXECUTABLE ${GLSLC_FOUND} CACHE FILEPATH "" FORCE)
endif()
message(STATUS "edge: glslc = ${Vulkan_GLSLC_EXECUTABLE}")
set(SPIRV-Headers_DIR ${CMAKE_CURRENT_LIST_DIR}/cmake CACHE PATH "" FORCE)
# vulkan-shaders-gen runs on the build machine, so it is built with the host compiler that
# app/build.gradle.kts passes in (EDGE_HOST_CC/EDGE_HOST_CXX).
if (EDGE_HOST_CC AND EDGE_HOST_CXX)
    # Forward slashes: these paths are written into a CMake file, where '' starts an escape.
    file(TO_CMAKE_PATH "${EDGE_HOST_CC}" EDGE_HOST_CC_PATH)
    file(TO_CMAKE_PATH "${EDGE_HOST_CXX}" EDGE_HOST_CXX_PATH)
    file(TO_CMAKE_PATH "${CMAKE_MAKE_PROGRAM}" EDGE_MAKE_PROGRAM)
    configure_file(${CMAKE_CURRENT_LIST_DIR}/cmake/host-toolchain.cmake.in ${CMAKE_BINARY_DIR}/edge-host-toolchain.cmake @ONLY)
    set(GGML_VULKAN_SHADERS_GEN_TOOLCHAIN ${CMAKE_BINARY_DIR}/edge-host-toolchain.cmake CACHE FILEPATH "" FORCE)
endif()

# --- OpenCL (Adreno-optimized GPU backend) ---------------------------------------------------
# The Khronos ICD loader is built only to link against; it is excluded from the APK so the
# device vendor's libOpenCL.so (declared with <uses-native-library>) is used at runtime.
set(GGML_OPENCL ON CACHE BOOL "" FORCE)
# The OpenCL kernels are embedded at build time by a Python script; the NDK toolchain restricts
# find_package to the Android sysroot, so look for the host interpreter explicitly.
# Python3_EXECUTABLE is passed in by app/build.gradle.kts.
if (NOT Python3_EXECUTABLE)
    message(FATAL_ERROR "Host Python not found: set edge.python in local.properties")
endif()
set(GGML_OPENCL_EMBED_KERNELS ON CACHE BOOL "" FORCE)
set(GGML_OPENCL_USE_ADRENO_KERNELS ON CACHE BOOL "" FORCE)
set(OPENCL_ICD_LOADER_HEADERS_DIR ${THIRD_PARTY}/OpenCL-Headers CACHE PATH "" FORCE)
set(OPENCL_ICD_LOADER_BUILD_TESTING OFF CACHE BOOL "" FORCE)
set(ENABLE_OPENCL_LAYERINFO OFF CACHE BOOL "" FORCE)
add_subdirectory(${THIRD_PARTY}/OpenCL-ICD-Loader opencl-icd-loader EXCLUDE_FROM_ALL)
set(OpenCL_INCLUDE_DIR ${THIRD_PARTY}/OpenCL-Headers CACHE PATH "" FORCE)
# Name the loader's CMake target rather than a file path (Gradle decides where libraries land).
set(OpenCL_LIBRARY OpenCL CACHE STRING "" FORCE)

add_subdirectory(${LLAMA_SRC} llama.cpp)

if (TARGET ggml-vulkan)
    target_include_directories(ggml-vulkan PRIVATE ${THIRD_PARTY}/SPIRV-Headers/include)
endif()

# --- JNI bridge -------------------------------------------------------------------------------
add_library(edge_llama SHARED edge_llama.cpp edge_chat.cpp)
target_include_directories(edge_llama PRIVATE ${LLAMA_SRC}/common ${LLAMA_SRC}/include ${LLAMA_SRC}/ggml/include ${LLAMA_SRC}/tools/mtmd)
target_link_libraries(edge_llama PRIVATE llama llama-common mtmd ggml android log)
