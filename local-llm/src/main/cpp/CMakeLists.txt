cmake_minimum_required(VERSION 3.31.6)

project("ai-chat" VERSION 1.0.0 LANGUAGES C CXX)

set(CMAKE_C_STANDARD 11)
set(CMAKE_C_STANDARD_REQUIRED true)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED true)

# Assertion and diagnostic strings must not expose the release builder's home
# directory. Apply this before llama.cpp creates any targets so every native
# dependency receives the same reproducible path mapping.
get_filename_component(REFRATOR_SOURCE_ROOT "${CMAKE_CURRENT_LIST_DIR}/../../../.." REALPATH)
file(TO_NATIVE_PATH "${REFRATOR_SOURCE_ROOT}" REFRATOR_SOURCE_ROOT_NATIVE)
add_compile_options(
        "-ffile-prefix-map=${REFRATOR_SOURCE_ROOT}=."
        "-fdebug-prefix-map=${REFRATOR_SOURCE_ROOT}=."
        "-fmacro-prefix-map=${REFRATOR_SOURCE_ROOT}=."
        "-ffile-prefix-map=${REFRATOR_SOURCE_ROOT_NATIVE}=."
        "-fdebug-prefix-map=${REFRATOR_SOURCE_ROOT_NATIVE}=."
        "-fmacro-prefix-map=${REFRATOR_SOURCE_ROOT_NATIVE}=.")

set(CMAKE_C_FLAGS   "${CMAKE_C_FLAGS}"   CACHE STRING "" FORCE)
set(CMAKE_CXX_FLAGS "${CMAKE_CXX_FLAGS}" CACHE STRING "" FORCE)

# --------------------------------------------------------------------------
# AI Chat library
# --------------------------------------------------------------------------

if(DEFINED ANDROID_ABI)
    message(STATUS "Detected Android ABI: ${ANDROID_ABI}")
    if(ANDROID_ABI STREQUAL "arm64-v8a")
        set(GGML_SYSTEM_ARCH "ARM")
        # Keep the first device build portable and avoid the very deep
        # FetchContent tree used by KleidiAI on Windows hosts.
        set(GGML_CPU_KLEIDIAI OFF)
        # Clang embeds the absolute source path in ARM OpenMP ident records,
        # bypassing its prefix-map flags. llama.cpp already owns its worker
        # thread pool, so keep OpenMP off for reproducible, privacy-safe APKs.
        set(GGML_OPENMP OFF)
    elseif(ANDROID_ABI STREQUAL "x86_64")
        set(GGML_SYSTEM_ARCH "x86")
        set(GGML_CPU_KLEIDIAI OFF)
        set(GGML_OPENMP OFF)
    else()
        message(FATAL_ERROR "Unsupported ABI: ${ANDROID_ABI}")
    endif()
endif()

set(LLAMA_SRC ${CMAKE_CURRENT_LIST_DIR}/../../../../third_party/llama.cpp)
add_subdirectory(${LLAMA_SRC} build-llama)

add_library(${CMAKE_PROJECT_NAME} SHARED
        ai_chat.cpp)

target_compile_definitions(${CMAKE_PROJECT_NAME} PRIVATE
        GGML_SYSTEM_ARCH=${GGML_SYSTEM_ARCH}
        GGML_CPU_KLEIDIAI=$<BOOL:${GGML_CPU_KLEIDIAI}>
        GGML_OPENMP=$<BOOL:${GGML_OPENMP}>
)

target_include_directories(${CMAKE_PROJECT_NAME} PRIVATE
        ${LLAMA_SRC}
        ${LLAMA_SRC}/common
        ${LLAMA_SRC}/include
        ${LLAMA_SRC}/ggml/include
        ${LLAMA_SRC}/ggml/src)

target_link_libraries(${CMAKE_PROJECT_NAME}
        llama
        llama-common
        android
        log)
