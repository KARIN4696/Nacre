#include <jni.h>
#include <android/log.h>
#include <string>
#include <mutex>

#define TAG "NacreWhisperJNI"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, TAG, __VA_ARGS__)

#ifdef WHISPER_AVAILABLE
#include "whisper.h"
#endif

static std::mutex g_mutex;

#ifdef WHISPER_AVAILABLE
static struct whisper_context* g_ctx = nullptr;
#endif
static bool g_model_loaded = false;

extern "C" {

JNIEXPORT jboolean JNICALL
Java_space_manus_nacre_ai_WhisperJni_loadModel(JNIEnv* env, jobject, jstring model_path) {
#ifdef WHISPER_AVAILABLE
    std::lock_guard<std::mutex> lock(g_mutex);

    if (g_ctx) {
        whisper_free(g_ctx);
        g_ctx = nullptr;
    }

    const char* path = env->GetStringUTFChars(model_path, nullptr);
    LOGI("Loading Whisper model from: %s", path);

    struct whisper_context_params params = whisper_context_default_params();
    g_ctx = whisper_init_from_file_with_params(path, params);
    env->ReleaseStringUTFChars(model_path, path);

    if (!g_ctx) {
        LOGE("Failed to load Whisper model");
        g_model_loaded = false;
        return JNI_FALSE;
    }

    g_model_loaded = true;
    LOGI("Whisper model loaded successfully");
    return JNI_TRUE;
#else
    LOGE("Whisper not available (stub build)");
    return JNI_FALSE;
#endif
}

JNIEXPORT void JNICALL
Java_space_manus_nacre_ai_WhisperJni_unloadModel(JNIEnv*, jobject) {
#ifdef WHISPER_AVAILABLE
    std::lock_guard<std::mutex> lock(g_mutex);
    if (g_ctx) {
        whisper_free(g_ctx);
        g_ctx = nullptr;
    }
#endif
    g_model_loaded = false;
    LOGI("Whisper model unloaded");
}

JNIEXPORT jboolean JNICALL
Java_space_manus_nacre_ai_WhisperJni_isModelLoaded(JNIEnv*, jobject) {
    return g_model_loaded ? JNI_TRUE : JNI_FALSE;
}

/**
 * Configure whisper_full_params with quality-optimized settings for Japanese/English.
 */
#ifdef WHISPER_AVAILABLE
static void configure_quality_params(struct whisper_full_params& params, const char* lang, const char* initial_prompt) {
    // Language: "auto" enables auto-detection
    params.language = (lang != nullptr && strlen(lang) > 0) ? lang : "auto";

    // Threading: 1 thread to avoid ggml threadpool deadlock on big.LITTLE ARM64
    // (whisper.cpp #2725 — barrier spin-lock hangs on cross-cluster cache-line bouncing)
    params.n_threads = 1;

    // Timestamps off — reduces overhead for streaming chunks
    params.no_timestamps = true;

    // Single segment for short chunks (typical voice input chunks are 1-5s)
    params.single_segment = true;

    // Suppress blank segments — reduces hallucination on near-silence
    params.suppress_blank = true;

    // Temperature 0.0 = greedy decoding (deterministic, fastest)
    params.temperature = 0.0f;
    // Allow temperature fallback to avoid infinite decode loop (whisper.cpp #508)
    params.temperature_inc = 0.2f;

    // Entropy threshold: skip segments with high entropy (likely noise/hallucination)
    // Default is 2.4; lower = stricter filtering
    params.entropy_thold = 2.4f;

    // Log probability threshold: skip segments with low average log probability
    // Default is -1.0; higher = stricter filtering
    params.logprob_thold = -1.0f;

    // No context from previous decode (we handle context via initial_prompt)
    params.no_context = true;

    // Context priming: pass previous text to maintain coherence across chunks
    if (initial_prompt != nullptr && strlen(initial_prompt) > 0) {
        params.initial_prompt = initial_prompt;
    }

    // Suppress printing
    params.print_special = false;
    params.print_progress = false;
    params.print_realtime = false;
    params.print_timestamps = false;
}
#endif

JNIEXPORT jstring JNICALL
Java_space_manus_nacre_ai_WhisperJni_transcribe(JNIEnv* env, jobject, jfloatArray audio_data, jstring language) {
#ifdef WHISPER_AVAILABLE
    std::lock_guard<std::mutex> lock(g_mutex);

    if (!g_ctx) {
        return env->NewStringUTF("");
    }

    jsize len = env->GetArrayLength(audio_data);
    jfloat* data = env->GetFloatArrayElements(audio_data, nullptr);

    const char* lang = env->GetStringUTFChars(language, nullptr);

    struct whisper_full_params params = whisper_full_default_params(WHISPER_SAMPLING_GREEDY);
    configure_quality_params(params, lang, nullptr);

    int result = whisper_full(g_ctx, params, data, len);

    env->ReleaseFloatArrayElements(audio_data, data, JNI_ABORT);
    env->ReleaseStringUTFChars(language, lang);

    if (result != 0) {
        LOGE("Whisper transcription failed: %d", result);
        return env->NewStringUTF("");
    }

    int n_segments = whisper_full_n_segments(g_ctx);
    std::string text;
    for (int i = 0; i < n_segments; i++) {
        text += whisper_full_get_segment_text(g_ctx, i);
    }

    LOGI("Transcription: %s", text.c_str());
    return env->NewStringUTF(text.c_str());
#else
    return env->NewStringUTF("");
#endif
}

JNIEXPORT jstring JNICALL
Java_space_manus_nacre_ai_WhisperJni_transcribeWithContext(JNIEnv* env, jobject, jfloatArray audio_data, jstring language, jstring initial_prompt) {
#ifdef WHISPER_AVAILABLE
    std::lock_guard<std::mutex> lock(g_mutex);

    if (!g_ctx) {
        return env->NewStringUTF("");
    }

    jsize len = env->GetArrayLength(audio_data);
    jfloat* data = env->GetFloatArrayElements(audio_data, nullptr);

    const char* lang = env->GetStringUTFChars(language, nullptr);
    const char* prompt = env->GetStringUTFChars(initial_prompt, nullptr);

    struct whisper_full_params params = whisper_full_default_params(WHISPER_SAMPLING_GREEDY);
    configure_quality_params(params, lang, prompt);

    int result = whisper_full(g_ctx, params, data, len);

    env->ReleaseFloatArrayElements(audio_data, data, JNI_ABORT);
    env->ReleaseStringUTFChars(language, lang);
    env->ReleaseStringUTFChars(initial_prompt, prompt);

    if (result != 0) {
        LOGE("Whisper transcription failed: %d", result);
        return env->NewStringUTF("");
    }

    int n_segments = whisper_full_n_segments(g_ctx);
    std::string text;
    for (int i = 0; i < n_segments; i++) {
        text += whisper_full_get_segment_text(g_ctx, i);
    }

    LOGI("Transcription (with context): %s", text.c_str());
    return env->NewStringUTF(text.c_str());
#else
    return env->NewStringUTF("");
#endif
}

} // extern "C"
