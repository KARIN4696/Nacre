#include <jni.h>
#include <android/log.h>
#include <string>
#include <mutex>
#include <atomic>

#define TAG "NacreLlamaJNI"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, TAG, __VA_ARGS__)

#ifdef LLAMA_AVAILABLE
#include "llama.h"
#include "common.h"
#endif

static std::mutex g_mutex;

#ifdef LLAMA_AVAILABLE
static llama_model* g_model = nullptr;
static llama_context* g_ctx = nullptr;
#endif
static bool g_model_loaded = false;
static std::atomic<bool> g_cancel{false};

extern "C" {

JNIEXPORT jboolean JNICALL
Java_space_manus_nacre_ai_LlamaJni_loadModel(JNIEnv* env, jobject, jstring model_path) {
#ifdef LLAMA_AVAILABLE
    std::lock_guard<std::mutex> lock(g_mutex);

    // Unload existing
    if (g_ctx) { llama_free(g_ctx); g_ctx = nullptr; }
    if (g_model) { llama_free_model(g_model); g_model = nullptr; }

    const char* path = env->GetStringUTFChars(model_path, nullptr);
    LOGI("Loading LLM model from: %s", path);

    llama_model_params model_params = llama_model_default_params();
    model_params.n_gpu_layers = 0; // CPU only on mobile

    g_model = llama_load_model_from_file(path, model_params);
    env->ReleaseStringUTFChars(model_path, path);

    if (!g_model) {
        LOGE("Failed to load LLM model");
        g_model_loaded = false;
        return JNI_FALSE;
    }

    llama_context_params ctx_params = llama_context_default_params();
    ctx_params.n_ctx = 2048;
    ctx_params.n_threads = 4;
    ctx_params.n_threads_batch = 4;

    g_ctx = llama_new_context_with_model(g_model, ctx_params);
    if (!g_ctx) {
        LOGE("Failed to create LLM context");
        llama_free_model(g_model);
        g_model = nullptr;
        g_model_loaded = false;
        return JNI_FALSE;
    }

    g_model_loaded = true;
    LOGI("LLM model loaded successfully");
    return JNI_TRUE;
#else
    LOGE("llama.cpp not available (stub build)");
    return JNI_FALSE;
#endif
}

JNIEXPORT void JNICALL
Java_space_manus_nacre_ai_LlamaJni_unloadModel(JNIEnv*, jobject) {
#ifdef LLAMA_AVAILABLE
    std::lock_guard<std::mutex> lock(g_mutex);
    if (g_ctx) { llama_free(g_ctx); g_ctx = nullptr; }
    if (g_model) { llama_free_model(g_model); g_model = nullptr; }
#endif
    g_model_loaded = false;
    LOGI("LLM model unloaded");
}

JNIEXPORT jboolean JNICALL
Java_space_manus_nacre_ai_LlamaJni_isModelLoaded(JNIEnv*, jobject) {
    return g_model_loaded ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT void JNICALL
Java_space_manus_nacre_ai_LlamaJni_cancelGeneration(JNIEnv*, jobject) {
    g_cancel.store(true);
}

JNIEXPORT jstring JNICALL
Java_space_manus_nacre_ai_LlamaJni_generate(JNIEnv* env, jobject, jstring prompt_str, jint max_tokens) {
#ifdef LLAMA_AVAILABLE
    std::lock_guard<std::mutex> lock(g_mutex);
    g_cancel.store(false);

    if (!g_ctx || !g_model) {
        return env->NewStringUTF("");
    }

    const char* prompt = env->GetStringUTFChars(prompt_str, nullptr);
    std::string prompt_text(prompt);
    env->ReleaseStringUTFChars(prompt_str, prompt);

    // Tokenize
    const int n_prompt_max = prompt_text.length() + 256;
    std::vector<llama_token> tokens(n_prompt_max);
    int n_tokens = llama_tokenize(g_model, prompt_text.c_str(), prompt_text.length(),
                                   tokens.data(), n_prompt_max, true, true);
    if (n_tokens < 0) {
        LOGE("Tokenization failed");
        return env->NewStringUTF("");
    }
    tokens.resize(n_tokens);

    // Clear KV cache
    llama_kv_cache_clear(g_ctx);

    // Evaluate prompt
    llama_batch batch = llama_batch_init(n_tokens, 0, 1);
    for (int i = 0; i < n_tokens; i++) {
        llama_batch_add(batch, tokens[i], i, {0}, false);
    }
    batch.logits[batch.n_tokens - 1] = true;

    if (llama_decode(g_ctx, batch) != 0) {
        LOGE("Prompt eval failed");
        llama_batch_free(batch);
        return env->NewStringUTF("");
    }

    // Generate
    std::string result;
    int n_cur = n_tokens;

    for (int i = 0; i < max_tokens; i++) {
        if (g_cancel.load()) {
            LOGI("Generation cancelled");
            break;
        }

        auto* logits = llama_get_logits_ith(g_ctx, batch.n_tokens - 1);
        llama_token new_token = llama_sample_token_greedy(g_ctx, logits,
                                                           llama_n_vocab(g_model));

        if (llama_token_is_eog(g_model, new_token)) break;

        char buf[256];
        int len = llama_token_to_piece(g_model, new_token, buf, sizeof(buf), 0, true);
        if (len > 0) {
            result.append(buf, len);
        }

        // Prepare next batch
        llama_batch_clear(batch);
        llama_batch_add(batch, new_token, n_cur, {0}, true);
        n_cur++;

        if (llama_decode(g_ctx, batch) != 0) {
            LOGE("Decode failed at token %d", i);
            break;
        }
    }

    llama_batch_free(batch);
    LOGI("Generated %zu chars", result.size());
    return env->NewStringUTF(result.c_str());
#else
    return env->NewStringUTF("");
#endif
}

} // extern "C"
