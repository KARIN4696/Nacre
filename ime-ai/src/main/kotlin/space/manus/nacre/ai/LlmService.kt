package space.manus.nacre.ai

import android.app.Service
import android.content.Intent
import android.os.Binder
import android.os.IBinder
import android.util.Log
import kotlinx.coroutines.*
import java.io.File

/**
 * LLM text transformation service.
 *
 * Runs in a separate process (android:process=":llm") for crash isolation.
 * Uses llama.cpp via JNI for fully offline text transformation.
 *
 * Capabilities: translation, style conversion (keigo), summarization, code generation.
 * Model: Gemma 3 1B INT4 (~600MB)
 */
class LlmService : Service() {

    private val binder = LlmBinder()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    var isModelLoaded = false
        private set
    var isGenerating = false
        private set

    private var modelPath: String? = null

    inner class LlmBinder : Binder() {
        fun getService(): LlmService = this@LlmService
    }

    override fun onBind(intent: Intent?): IBinder = binder

    override fun onCreate() {
        super.onCreate()
        Log.d(TAG, "LlmService created")
    }

    /**
     * Load the LLM model.
     * Model file: gemma-3-1b-q4.gguf (~600MB)
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
                // TODO: Load model via llama.cpp JNI
                // nativeLoadModel(path, nThreads = 4, contextSize = 2048)
                isModelLoaded = true
                Log.i(TAG, "LLM model loaded: $path (${file.length() / 1024 / 1024}MB)")
                withContext(Dispatchers.Main) { callback(true) }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to load LLM model", e)
                withContext(Dispatchers.Main) { callback(false) }
            }
        }
    }

    fun unloadModel() {
        // TODO: nativeUnloadModel()
        isModelLoaded = false
        modelPath = null
        Log.d(TAG, "LLM model unloaded")
    }

    /**
     * Transform text using the LLM.
     *
     * @param text Input text to transform
     * @param instruction Transformation instruction (e.g., "英語にして", "敬語にして", "コードにして")
     * @param onResult Callback with transformed text
     * @param onError Callback on error
     */
    fun transform(
        text: String,
        instruction: String,
        onResult: (String) -> Unit,
        onError: ((String) -> Unit)? = null,
    ) {
        if (!isModelLoaded) {
            onError?.invoke("Model not loaded")
            return
        }
        if (isGenerating) {
            onError?.invoke("Already generating")
            return
        }

        isGenerating = true

        scope.launch {
            try {
                val prompt = buildPrompt(text, instruction)
                // TODO: Generate via llama.cpp JNI
                // val result = nativeGenerate(prompt, maxTokens = 512, temperature = 0.3f)
                val result = "[LLM not yet available] $text" // Placeholder
                Log.d(TAG, "Transform: '$instruction' on '${text.take(50)}'")

                withContext(Dispatchers.Main) { onResult(result) }
            } catch (e: Exception) {
                Log.e(TAG, "Generation error", e)
                withContext(Dispatchers.Main) { onError?.invoke(e.message ?: "Unknown error") }
            } finally {
                isGenerating = false
            }
        }
    }

    private fun buildPrompt(text: String, instruction: String): String {
        return """<start_of_turn>user
以下のテキストを「$instruction」してください。元のテキストの意味を保持してください。

テキスト: $text
<end_of_turn>
<start_of_turn>model
"""
    }

    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        if (level >= TRIM_MEMORY_RUNNING_LOW) {
            Log.w(TAG, "Low memory - unloading LLM model")
            unloadModel()
        }
    }

    override fun onDestroy() {
        scope.cancel()
        unloadModel()
        super.onDestroy()
    }

    companion object {
        private const val TAG = "LlmService"
    }

    // JNI declarations (to be implemented with NDK)
    // private external fun nativeLoadModel(path: String, nThreads: Int, contextSize: Int): Boolean
    // private external fun nativeUnloadModel()
    // private external fun nativeGenerate(prompt: String, maxTokens: Int, temperature: Float): String
}
