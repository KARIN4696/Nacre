package space.manus.nacre.ai

import android.util.Log

/**
 * JNI bridge to whisper.cpp for offline speech-to-text.
 * Native library: libnacre-ai.so
 */
object WhisperJni {

    private const val TAG = "WhisperJni"
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

    /** Load a whisper.cpp model file (e.g. whisper-base.bin). Blocks until loaded. */
    external fun loadModel(modelPath: String): Boolean

    /** Unload the current model and free memory. */
    external fun unloadModel()

    /** Check if a model is currently loaded. */
    external fun isModelLoaded(): Boolean

    /**
     * Transcribe audio samples to text.
     * @param audioData PCM float32 samples at 16kHz mono
     * @param language BCP-47 language code (e.g. "ja", "en", "auto")
     * @return transcribed text
     */
    external fun transcribe(audioData: FloatArray, language: String): String

    /**
     * Transcribe audio samples with context priming via initial_prompt.
     * The initial_prompt provides context from previously transcribed text,
     * helping Whisper maintain coherence across chunks.
     *
     * @param audioData PCM float32 samples at 16kHz mono
     * @param language BCP-47 language code (e.g. "ja", "en", "auto")
     * @param initialPrompt previous text context (max ~200 chars recommended)
     * @return transcribed text
     */
    external fun transcribeWithContext(audioData: FloatArray, language: String, initialPrompt: String): String
}
