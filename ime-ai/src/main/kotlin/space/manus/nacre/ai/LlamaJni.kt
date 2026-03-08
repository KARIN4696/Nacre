package space.manus.nacre.ai

import android.util.Log

/**
 * JNI bridge to llama.cpp for local LLM inference.
 * Native library: libnacre-ai.so
 */
object LlamaJni {

    private const val TAG = "LlamaJni"
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

    /** Load a GGUF model file. Blocks until loaded (3-8 seconds). */
    external fun loadModel(modelPath: String): Boolean

    /** Unload the current model and free memory. */
    external fun unloadModel()

    /** Check if a model is currently loaded. */
    external fun isModelLoaded(): Boolean

    /** Cancel an in-progress generation. */
    external fun cancelGeneration()

    /**
     * Generate text from a prompt.
     * @param prompt the input prompt
     * @param maxTokens maximum tokens to generate
     * @return generated text
     */
    external fun generate(prompt: String, maxTokens: Int): String
}
