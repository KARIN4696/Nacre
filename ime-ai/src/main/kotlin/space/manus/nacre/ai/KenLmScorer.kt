package space.manus.nacre.ai

import android.util.Log

/**
 * High-level KenLM scorer for Japanese conversion candidates.
 *
 * Wraps KenLmJni with convenient methods for scoring Viterbi candidate segments.
 * Thread-safe (JNI layer uses mutex).
 */
class KenLmScorer {

    private var modelLoaded = false

    /**
     * Load a KenLM binary model file.
     * @param modelPath Absolute path to .klm file
     * @return true if loaded successfully
     */
    fun load(modelPath: String): Boolean {
        if (!KenLmJni.isAvailable()) {
            Log.w(TAG, "KenLM native library not available")
            return false
        }
        modelLoaded = KenLmJni.loadModel(modelPath)
        if (modelLoaded) {
            Log.i(TAG, "KenLM model loaded (order=${KenLmJni.getOrder()})")
        }
        return modelLoaded
    }

    fun isReady(): Boolean = KenLmJni.isAvailable() && modelLoaded

    /**
     * Score a single candidate given its word segments.
     * @param segments List of surface forms from Viterbi segmentation
     * @param precedingContext Recent committed text (for cross-sentence context)
     * @return log10 probability sum (higher = more likely)
     */
    fun score(segments: List<String>, precedingContext: String = ""): Float {
        if (!isReady()) return 0f
        val sentence = buildSentence(segments, precedingContext)
        return KenLmJni.scoreSentence(sentence)
    }

    /**
     * Score multiple candidates in batch (more efficient than individual calls).
     * @param candidates List of segment lists
     * @param precedingContext Recent committed text
     * @return FloatArray of log10 probability sums
     */
    fun scoreBatch(candidates: List<List<String>>, precedingContext: String = ""): FloatArray {
        if (!isReady()) return FloatArray(candidates.size) { 0f }
        val sentences = candidates.map { buildSentence(it, precedingContext) }.toTypedArray()
        return KenLmJni.scoreBatch(sentences)
    }

    fun unload() {
        KenLmJni.unloadModel()
        modelLoaded = false
    }

    private fun buildSentence(segments: List<String>, precedingContext: String): String {
        return buildString {
            if (precedingContext.isNotEmpty()) {
                // Use last ~20 chars of preceding context as additional words
                append(precedingContext.takeLast(20))
                append(" ")
            }
            append(segments.joinToString(" "))
        }
    }

    companion object {
        private const val TAG = "KenLmScorer"
    }
}
