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
     * @param language BCP-47 language code (e.g. "ja", "en")
     * @return transcribed text
     */
    external fun transcribe(audioData: FloatArray, language: String): String
}
