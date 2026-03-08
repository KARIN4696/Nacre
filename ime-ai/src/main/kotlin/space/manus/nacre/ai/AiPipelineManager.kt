package space.manus.nacre.ai

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.BatteryManager
import android.os.IBinder
import android.util.Log

/**
 * AI Pipeline Manager.
 *
 * Orchestrates the voice input → LLM transformation pipeline.
 * Manages service connections to WhisperService and LlmService.
 *
 * Workflow:
 * 1. User triggers voice input (trackball up-swipe)
 * 2. WhisperService transcribes speech to text
 * 3. Text inserted immediately (or optionally sent to LLM)
 * 4. User can request transformation: "英語にして", "敬語にして", "コードにして"
 * 5. LlmService transforms text
 * 6. Result replaces the transcribed text
 */
class AiPipelineManager(private val context: Context) {

    private var whisperService: WhisperService? = null
    private var llmService: LlmService? = null
    private var whisperBound = false
    private var llmBound = false

    var isAvailable = false
        private set

    var onTranscriptionResult: ((String) -> Unit)? = null
    var onTransformResult: ((String) -> Unit)? = null
    var onError: ((String) -> Unit)? = null
    var onStatusChanged: ((AiStatus) -> Unit)? = null

    enum class AiStatus {
        Idle,
        Listening,
        Transcribing,
        Transforming,
        ModelLoading,
        Error,
    }

    var status: AiStatus = AiStatus.Idle
        private set(value) {
            field = value
            onStatusChanged?.invoke(value)
        }

    private val whisperConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            whisperService = (service as WhisperService.WhisperBinder).getService()
            whisperBound = true
            checkAvailability()
            Log.d(TAG, "WhisperService connected")
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            whisperService = null
            whisperBound = false
            checkAvailability()
        }
    }

    private val llmConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            llmService = (service as LlmService.LlmBinder).getService()
            llmBound = true
            checkAvailability()
            Log.d(TAG, "LlmService connected")
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            llmService = null
            llmBound = false
            checkAvailability()
        }
    }

    fun bindServices() {
        try {
            context.bindService(
                Intent(context, WhisperService::class.java),
                whisperConnection,
                Context.BIND_AUTO_CREATE,
            )
            context.bindService(
                Intent(context, LlmService::class.java),
                llmConnection,
                Context.BIND_AUTO_CREATE,
            )
        } catch (e: Exception) {
            Log.e(TAG, "Failed to bind AI services", e)
        }
    }

    fun unbindServices() {
        try {
            if (whisperBound) {
                context.unbindService(whisperConnection)
                whisperBound = false
            }
            if (llmBound) {
                context.unbindService(llmConnection)
                llmBound = false
            }
        } catch (e: Exception) {
            Log.w(TAG, "Error unbinding AI services", e)
        }
        whisperService = null
        llmService = null
        isAvailable = false
    }

    /**
     * Load AI models from the given directory.
     */
    fun loadModels(modelsDir: String) {
        if (!isBatteryOk()) {
            onError?.invoke("Battery too low for AI features (< 20%)")
            return
        }

        status = AiStatus.ModelLoading

        whisperService?.loadModel("$modelsDir/whisper-base.bin") { whisperOk ->
            llmService?.loadModel("$modelsDir/gemma-3-1b-q4.gguf") { llmOk ->
                checkAvailability()
                status = if (isAvailable) AiStatus.Idle else AiStatus.Error
                if (!whisperOk) onError?.invoke("Failed to load Whisper model")
                if (!llmOk) onError?.invoke("Failed to load LLM model")
            }
        }
    }

    /**
     * Start voice input → transcription.
     */
    fun startVoiceInput() {
        val whisper = whisperService
        if (whisper == null || !whisper.isModelLoaded) {
            onError?.invoke("Whisper model not loaded")
            return
        }
        if (!isBatteryOk()) {
            onError?.invoke("Battery too low")
            return
        }

        status = AiStatus.Listening

        whisper.startRecognition(
            onResult = { text ->
                status = AiStatus.Idle
                onTranscriptionResult?.invoke(text)
            },
            onPartialResult = { partial ->
                status = AiStatus.Transcribing
            },
            onError = { error ->
                status = AiStatus.Error
                onError?.invoke(error)
            },
        )
    }

    fun stopVoiceInput() {
        whisperService?.stopRecognition()
        status = AiStatus.Idle
    }

    /**
     * Transform text using LLM.
     *
     * @param text Text to transform
     * @param instruction e.g., "英語にして", "敬語にして", "要約して", "コードにして"
     */
    fun transformText(text: String, instruction: String) {
        val llm = llmService
        if (llm == null || !llm.isModelLoaded) {
            onError?.invoke("LLM model not loaded")
            return
        }
        if (!isBatteryOk()) {
            onError?.invoke("Battery too low")
            return
        }

        status = AiStatus.Transforming

        llm.transform(
            text = text,
            instruction = instruction,
            onResult = { result ->
                status = AiStatus.Idle
                onTransformResult?.invoke(result)
            },
            onError = { error ->
                status = AiStatus.Error
                onError?.invoke(error)
            },
        )
    }

    fun unloadModels() {
        whisperService?.unloadModel()
        llmService?.unloadModel()
        checkAvailability()
        status = AiStatus.Idle
    }

    /**
     * Check if models exist on device.
     */
    fun areModelsDownloaded(): Boolean {
        val modelsDir = context.filesDir.resolve("models")
        val whisperFile = modelsDir.resolve("whisper-base.bin")
        val llmFile = modelsDir.resolve("gemma-3-1b-q4.gguf")
        return whisperFile.exists() && llmFile.exists()
    }

    fun getModelsDir(): String = context.filesDir.resolve("models").absolutePath

    private fun checkAvailability() {
        isAvailable = whisperService?.isModelLoaded == true || llmService?.isModelLoaded == true
    }

    private fun isBatteryOk(): Boolean {
        val bm = context.getSystemService(Context.BATTERY_SERVICE) as? BatteryManager ?: return true
        val level = bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
        return level > BATTERY_THRESHOLD
    }

    companion object {
        private const val TAG = "AiPipelineManager"
        private const val BATTERY_THRESHOLD = 20
    }
}
