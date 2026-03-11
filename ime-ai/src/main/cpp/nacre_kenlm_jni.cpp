#include <jni.h>
#include <android/log.h>
#include <string>
#include <mutex>
#include <vector>
#include <sstream>

#define TAG "NacreKenLmJNI"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, TAG, __VA_ARGS__)

#ifdef KENLM_AVAILABLE
#include "lm/model.hh"
#include "lm/enumerate_vocab.hh"
#endif

static std::mutex g_mutex;

#ifdef KENLM_AVAILABLE
// Use base::Model to support both probing and trie formats
static lm::base::Model* g_model = nullptr;
#endif
static bool g_model_loaded = false;

extern "C" {

JNIEXPORT jboolean JNICALL
Java_space_manus_nacre_ai_KenLmJni_loadModel(JNIEnv* env, jobject, jstring model_path) {
#ifdef KENLM_AVAILABLE
    std::lock_guard<std::mutex> lock(g_mutex);

    // Unload existing
    if (g_model) {
        delete g_model;
        g_model = nullptr;
    }

    const char* path = env->GetStringUTFChars(model_path, nullptr);
    LOGI("Loading KenLM model from: %s", path);

    try {
        lm::ngram::Config config;
        config.load_method = util::LAZY; // Memory-mapped lazy loading for mobile
        // LoadVirtual auto-detects probing/trie/etc format
        g_model = lm::ngram::LoadVirtual(path, config);
        g_model_loaded = true;
        LOGI("KenLM model loaded successfully (order=%d)", (int)g_model->Order());
    } catch (const std::exception& e) {
        LOGE("Failed to load KenLM model: %s", e.what());
        g_model_loaded = false;
        env->ReleaseStringUTFChars(model_path, path);
        return JNI_FALSE;
    }

    env->ReleaseStringUTFChars(model_path, path);
    return JNI_TRUE;
#else
    LOGE("KenLM not available (stub build)");
    return JNI_FALSE;
#endif
}

JNIEXPORT void JNICALL
Java_space_manus_nacre_ai_KenLmJni_unloadModel(JNIEnv*, jobject) {
#ifdef KENLM_AVAILABLE
    std::lock_guard<std::mutex> lock(g_mutex);
    if (g_model) {
        delete g_model;
        g_model = nullptr;
    }
#endif
    g_model_loaded = false;
    LOGI("KenLM model unloaded");
}

JNIEXPORT jboolean JNICALL
Java_space_manus_nacre_ai_KenLmJni_isModelLoaded(JNIEnv*, jobject) {
    return g_model_loaded ? JNI_TRUE : JNI_FALSE;
}

/**
 * Score a space-separated sentence.
 * Returns sum of log10 probabilities (higher = more likely).
 * For Japanese, input is space-separated surface forms from Viterbi segmentation.
 */
JNIEXPORT jfloat JNICALL
Java_space_manus_nacre_ai_KenLmJni_scoreSentence(JNIEnv* env, jobject, jstring sentence_str) {
#ifdef KENLM_AVAILABLE
    std::lock_guard<std::mutex> lock(g_mutex);
    if (!g_model) return -100.0f;

    const char* sentence = env->GetStringUTFChars(sentence_str, nullptr);

    lm::ngram::State state, out_state;
    g_model->BeginSentenceWrite(&state);

    float total_score = 0.0f;
    int word_count = 0;

    // Tokenize by spaces
    std::istringstream iss(sentence);
    std::string word;
    while (iss >> word) {
        lm::FullScoreReturn ret = g_model->BaseFullScore(&state, g_model->BaseVocabulary().Index(word), &out_state);
        total_score += ret.prob;
        state = out_state;
        word_count++;
    }

    // End-of-sentence
    lm::FullScoreReturn eos_ret = g_model->BaseFullScore(&state, g_model->BaseVocabulary().EndSentence(), &out_state);
    total_score += eos_ret.prob;

    env->ReleaseStringUTFChars(sentence_str, sentence);
    return total_score;
#else
    return -100.0f;
#endif
}

/**
 * Score multiple sentences in batch.
 * More efficient than calling scoreSentence repeatedly (single lock acquisition).
 */
JNIEXPORT jfloatArray JNICALL
Java_space_manus_nacre_ai_KenLmJni_scoreBatch(JNIEnv* env, jobject, jobjectArray sentences) {
#ifdef KENLM_AVAILABLE
    std::lock_guard<std::mutex> lock(g_mutex);

    int count = env->GetArrayLength(sentences);
    jfloatArray result = env->NewFloatArray(count);
    if (!g_model || count == 0) return result;

    std::vector<float> scores(count);

    for (int i = 0; i < count; i++) {
        jstring jstr = (jstring)env->GetObjectArrayElement(sentences, i);
        const char* sentence = env->GetStringUTFChars(jstr, nullptr);

        lm::ngram::State state, out_state;
        g_model->BeginSentenceWrite(&state);

        float total = 0.0f;
        std::istringstream iss(sentence);
        std::string word;
        while (iss >> word) {
            lm::FullScoreReturn ret = g_model->BaseFullScore(&state, g_model->BaseVocabulary().Index(word), &out_state);
            total += ret.prob;
            state = out_state;
        }
        lm::FullScoreReturn eos_ret = g_model->BaseFullScore(&state, g_model->BaseVocabulary().EndSentence(), &out_state);
        total += eos_ret.prob;

        scores[i] = total;

        env->ReleaseStringUTFChars(jstr, sentence);
        env->DeleteLocalRef(jstr);
    }

    env->SetFloatArrayRegion(result, 0, count, scores.data());
    return result;
#else
    int count = env->GetArrayLength(sentences);
    jfloatArray result = env->NewFloatArray(count);
    std::vector<float> scores(count, -100.0f);
    env->SetFloatArrayRegion(result, 0, count, scores.data());
    return result;
#endif
}

JNIEXPORT jfloat JNICALL
Java_space_manus_nacre_ai_KenLmJni_perplexity(JNIEnv* env, jobject thiz, jstring sentence_str) {
#ifdef KENLM_AVAILABLE
    std::lock_guard<std::mutex> lock(g_mutex);
    if (!g_model) return 999999.0f;

    const char* sentence = env->GetStringUTFChars(sentence_str, nullptr);

    lm::ngram::State state, out_state;
    g_model->BeginSentenceWrite(&state);

    float total_score = 0.0f;
    int word_count = 0;

    std::istringstream iss(sentence);
    std::string word;
    while (iss >> word) {
        lm::FullScoreReturn ret = g_model->BaseFullScore(&state, g_model->BaseVocabulary().Index(word), &out_state);
        total_score += ret.prob;
        state = out_state;
        word_count++;
    }
    lm::FullScoreReturn eos_ret = g_model->BaseFullScore(&state, g_model->BaseVocabulary().EndSentence(), &out_state);
    total_score += eos_ret.prob;
    word_count++; // EOS counts

    env->ReleaseStringUTFChars(sentence_str, sentence);

    // Perplexity = 10^(-total_score / word_count)
    if (word_count == 0) return 999999.0f;
    return (float)pow(10.0, -total_score / word_count);
#else
    return 999999.0f;
#endif
}

JNIEXPORT jint JNICALL
Java_space_manus_nacre_ai_KenLmJni_getOrder(JNIEnv*, jobject) {
#ifdef KENLM_AVAILABLE
    std::lock_guard<std::mutex> lock(g_mutex);
    if (!g_model) return 0;
    return (jint)g_model->Order();
#else
    return 0;
#endif
}

} // extern "C"
