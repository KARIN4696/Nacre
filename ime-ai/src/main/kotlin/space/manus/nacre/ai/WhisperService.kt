package space.manus.nacre.ai

import android.app.Service
import android.content.Intent
import android.os.Binder
import android.os.IBinder
import android.util.Log
import kotlinx.coroutines.*
import java.io.File

/**
 * Whisper speech-to-text service.
 *
 * Runs in a separate process (android:process=":whisper") for crash isolation.
 * Uses whisper.cpp via JNI for fully offline speech recognition.
 *
 * Phase 5 implementation: Currently provides the interface and model management.
 * JNI bindings for whisper.cpp will be added when NDK build is configured.
 */
class WhisperService : Service() {

    private val binder = WhisperBinder()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    var isModelLoaded = false
        private set
    var isRecognizing = false
        private set

    private var modelPath: String? = null
    private var onResult: ((String) -> Unit)? = null
    private var onPartialResult: ((String) -> Unit)? = null
    private var onError: ((String) -> Unit)? = null

    inner class WhisperBinder : Binder() {
        fun getService(): WhisperService = this@WhisperService
    }

    override fun onBind(intent: Intent?): IBinder = binder

    override fun onCreate() {
        super.onCreate()
        Log.d(TAG, "WhisperService created")
    }

    /**
     * Load the Whisper model from the given path.
     * Model file: whisper-base.bin (~142MB)
     */
    fun loadModel(path: String, callback: (Boolean) -> Unit) {
        scope.launch {
            try {
                val file = File(path)
                if (!file.exists()) {
                    Log.e(TAG, "Model file not found: $path")
                    withContext(Dispatchers.Main) { callback(false) }
                    return@launch
                }

                modelPath = path
                // TODO: Load model via JNI when whisper.cpp bindings are ready
                // nativeLoadModel(path)
                isModelLoaded = true
                Log.i(TAG, "Whisper model loaded: $path (${file.length() / 1024 / 1024}MB)")
                withContext(Dispatchers.Main) { callback(true) }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to load Whisper model", e)
                withContext(Dispatchers.Main) { callback(false) }
            }
        }
    }

    fun unloadModel() {
        // TODO: nativeUnloadModel()
        isModelLoaded = false
        modelPath = null
        Log.d(TAG, "Whisper model unloaded")
    }

    /**
     * Start speech recognition from audio input.
     */
    fun startRecognition(
        onResult: (String) -> Unit,
        onPartialResult: ((String) -> Unit)? = null,
        onError: ((String) -> Unit)? = null,
    ) {
        if (!isModelLoaded) {
            onError?.invoke("Model not loaded")
            return
        }
        if (isRecognizing) {
            onError?.invoke("Already recognizing")
            return
        }

        this.onResult = onResult
        this.onPartialResult = onPartialResult
        this.onError = onError
        isRecognizing = true

        scope.launch {
            try {
                // TODO: Start audio capture and feed to whisper.cpp
                // For now, use Android SpeechRecognizer as fallback
                Log.d(TAG, "Recognition started (whisper.cpp JNI pending)")
            } catch (e: Exception) {
                Log.e(TAG, "Recognition error", e)
                withContext(Dispatchers.Main) {
                    this@WhisperService.onError?.invoke(e.message ?: "Unknown error")
                }
                isRecognizing = false
            }
        }
    }

    fun stopRecognition() {
        isRecognizing = false
        // TODO: Stop audio capture and finalize recognition
        Log.d(TAG, "Recognition stopped")
    }

    override fun onDestroy() {
        scope.cancel()
        unloadModel()
        super.onDestroy()
    }

    companion object {
        private const val TAG = "WhisperService"
    }

    // JNI declarations (to be implemented with NDK)
    // private external fun nativeLoadModel(path: String): Boolean
    // private external fun nativeUnloadModel()
    // private external fun nativeTranscribe(audioData: ShortArray, sampleRate: Int): String
}
