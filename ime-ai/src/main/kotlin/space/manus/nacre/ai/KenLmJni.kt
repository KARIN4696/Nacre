package space.manus.nacre.ai

import android.util.Log

/**
 * JNI bridge to KenLM for N-gram language model scoring.
 * Native library: libnacre-ai.so
 *
 * Scores space-separated Japanese sentences using a 5-gram language model.
 * For Japanese, "words" are surface forms from Viterbi segmentation joined by spaces.
 */
object KenLmJni {

    private const val TAG = "KenLmJni"
    private var libraryLoaded = false

    init {
        try {
            System.loadLibrary("nacre-ai")
            libraryLoaded = true
            Log.i(TAG, "Native library loaded")
        } catch (e: UnsatisfiedLinkError) {
            Log.e(TAG, "Failed to load native library", e)
        }
    }

    fun isAvailable(): Boolean = libraryLoaded

    /** Load a binary KenLM model (.klm / .probing). Blocks until loaded. */
    external fun loadModel(modelPath: String): Boolean

    /** Unload the current model and free memory. */
    external fun unloadModel()

    /** Check if a model is currently loaded. */
    external fun isModelLoaded(): Boolean

    /**
     * Score a space-separated sentence. Returns sum of log10 probabilities.
     * Higher score = more likely sentence.
     * e.g. "今日 は いい 天気 です ね" → -12.5
     */
    external fun scoreSentence(sentence: String): Float

    /**
     * Score multiple sentences in batch (single native lock acquisition).
     * @return FloatArray of log10 probability sums, one per sentence.
     */
    external fun scoreBatch(sentences: Array<String>): FloatArray

    /**
     * Get perplexity of a sentence (lower = more natural).
     */
    external fun perplexity(sentence: String): Float

    /** Get the N-gram order of the loaded model (e.g. 5 for 5-gram). */
    external fun getOrder(): Int
}
