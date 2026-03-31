cmake_minimum_required(VERSION 3.22)
project(nacre-ai LANGUAGES C CXX)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)
set(CMAKE_C_STANDARD 11)

# === Whisper.cpp (optional - sources cloned by CI) ===
set(WHISPER_DIR "${CMAKE_SOURCE_DIR}/whisper")
if(EXISTS "${WHISPER_DIR}/whisper.cpp")
    file(GLOB WHISPER_C_SRCS "${WHISPER_DIR}/*.c")
    file(GLOB WHISPER_CPP_SRCS "${WHISPER_DIR}/whisper.cpp")
    # Exclude non-CPU backends if present
    list(FILTER WHISPER_C_SRCS EXCLUDE REGEX "ggml-(cuda|metal|vulkan|sycl|kompute|rpc)")

    add_library(whisper_lib STATIC
        ${WHISPER_CPP_SRCS}
        ${WHISPER_C_SRCS}
    )
    target_include_directories(whisper_lib PUBLIC "${WHISPER_DIR}")
    target_compile_definitions(whisper_lib PRIVATE
        GGML_USE_CPU
        NDEBUG
    )
    set(WHISPER_AVAILABLE TRUE)
    message(STATUS "whisper.cpp: ENABLED (GLOB)")
else()
    set(WHISPER_AVAILABLE FALSE)
    message(STATUS "whisper.cpp: DISABLED (sources not found)")
endif()

# --- llama.cpp ---
# Source: https://github.com/ggerganov/llama.cpp
# Place llama.cpp source in ime-ai/src/main/cpp/llama/
set(LLAMA_DIR ${CMAKE_SOURCE_DIR}/llama)
if(EXISTS ${LLAMA_DIR}/llama.cpp)
    add_library(llama STATIC
        ${LLAMA_DIR}/llama.cpp
        ${LLAMA_DIR}/llama-grammar.cpp
        ${LLAMA_DIR}/llama-sampling.cpp
        ${LLAMA_DIR}/llama-vocab.cpp
        ${LLAMA_DIR}/ggml.c
        ${LLAMA_DIR}/ggml-alloc.c
        ${LLAMA_DIR}/ggml-backend.c
        ${LLAMA_DIR}/ggml-quants.c
        ${LLAMA_DIR}/common/common.cpp
        ${LLAMA_DIR}/common/sampling.cpp
    )
    target_include_directories(llama PUBLIC
        ${LLAMA_DIR}
        ${LLAMA_DIR}/common
    )
    target_compile_definitions(llama PRIVATE
        GGML_USE_CPU
        NDEBUG
    )
    set(LLAMA_AVAILABLE TRUE)
else()
    message(WARNING "llama.cpp sources not found at ${LLAMA_DIR}. Building stub.")
    set(LLAMA_AVAILABLE FALSE)
endif()

# --- KenLM ---
# Source: https://github.com/kpu/kenlm
# Place kenlm source (lm/ + util/) in ime-ai/src/main/cpp/kenlm/
set(KENLM_DIR ${CMAKE_SOURCE_DIR}/kenlm)
if(EXISTS ${KENLM_DIR}/lm/model.cc)
    # Collect source files, excluding tests, mains, and benchmarks
    file(GLOB KENLM_LM_SRCS ${KENLM_DIR}/lm/*.cc)
    file(GLOB KENLM_UTIL_SRCS ${KENLM_DIR}/util/*.cc)
    file(GLOB KENLM_DCONV_SRCS ${KENLM_DIR}/util/double-conversion/*.cc)

    list(FILTER KENLM_LM_SRCS EXCLUDE REGEX ".*_main\\.cc$")
    list(FILTER KENLM_LM_SRCS EXCLUDE REGEX ".*_test\\.cc$")
    list(FILTER KENLM_LM_SRCS EXCLUDE REGEX ".*benchmark.*\\.cc$")
    list(FILTER KENLM_UTIL_SRCS EXCLUDE REGEX ".*_main\\.cc$")
    list(FILTER KENLM_UTIL_SRCS EXCLUDE REGEX ".*_test\\.cc$")
    list(FILTER KENLM_UTIL_SRCS EXCLUDE REGEX ".*benchmark.*\\.cc$")

    add_library(kenlm STATIC
        ${KENLM_LM_SRCS}
        ${KENLM_UTIL_SRCS}
        ${KENLM_DCONV_SRCS}
    )
    target_include_directories(kenlm PUBLIC ${KENLM_DIR})
    target_compile_definitions(kenlm PUBLIC
        KENLM_MAX_ORDER=5
        _LIBCPP_ENABLE_CXX17_REMOVED_UNARY_BINARY_FUNCTION
    )
    target_compile_definitions(kenlm PRIVATE
        HAVE_POSIX_MEMALIGN
        NDEBUG
    )
    # Suppress some warnings in KenLM code
    target_compile_options(kenlm PRIVATE -Wno-unused-parameter -Wno-sign-compare)
    set(KENLM_AVAILABLE TRUE)
else()
    message(WARNING "KenLM sources not found at ${KENLM_DIR}. Building stub.")
    set(KENLM_AVAILABLE FALSE)
endif()

# --- JNI Bridge ---
add_library(nacre-ai SHARED
    nacre_whisper_jni.cpp
    nacre_llama_jni.cpp
    nacre_kenlm_jni.cpp
)

target_include_directories(nacre-ai PRIVATE
    ${CMAKE_SOURCE_DIR}
)

find_library(log-lib log)
find_library(android-lib android)

target_link_libraries(nacre-ai
    ${log-lib}
    ${android-lib}
)

if(WHISPER_AVAILABLE)
    target_link_libraries(nacre-ai whisper_lib)
    target_compile_definitions(nacre-ai PRIVATE WHISPER_AVAILABLE)
endif()

if(LLAMA_AVAILABLE)
    target_link_libraries(nacre-ai llama)
    target_compile_definitions(nacre-ai PRIVATE LLAMA_AVAILABLE)
endif()

if(KENLM_AVAILABLE)
    target_link_libraries(nacre-ai kenlm)
    target_compile_definitions(nacre-ai PRIVATE KENLM_AVAILABLE)
endif()
