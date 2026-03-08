cmake_minimum_required(VERSION 3.22)
project(nacre-ai LANGUAGES C CXX)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)
set(CMAKE_C_STANDARD 11)

# --- whisper.cpp ---
# Source: https://github.com/ggerganov/whisper.cpp
# Place whisper.cpp source in ime-ai/src/main/cpp/whisper/
set(WHISPER_DIR ${CMAKE_SOURCE_DIR}/whisper)
if(EXISTS ${WHISPER_DIR}/whisper.cpp)
    add_library(whisper STATIC
        ${WHISPER_DIR}/whisper.cpp
        ${WHISPER_DIR}/ggml.c
        ${WHISPER_DIR}/ggml-alloc.c
        ${WHISPER_DIR}/ggml-backend.c
        ${WHISPER_DIR}/ggml-quants.c
    )
    target_include_directories(whisper PUBLIC ${WHISPER_DIR})
    target_compile_definitions(whisper PRIVATE
        GGML_USE_CPU
        NDEBUG
    )
    set(WHISPER_AVAILABLE TRUE)
else()
    message(WARNING "whisper.cpp sources not found at ${WHISPER_DIR}. Building stub.")
    set(WHISPER_AVAILABLE FALSE)
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

# --- JNI Bridge ---
add_library(nacre-ai SHARED
    nacre_whisper_jni.cpp
    nacre_llama_jni.cpp
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
    target_link_libraries(nacre-ai whisper)
    target_compile_definitions(nacre-ai PRIVATE WHISPER_AVAILABLE)
endif()

if(LLAMA_AVAILABLE)
    target_link_libraries(nacre-ai llama)
    target_compile_definitions(nacre-ai PRIVATE LLAMA_AVAILABLE)
endif()
