package space.manus.nacre.ime.input

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.BatteryManager
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.util.Log
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.content.ContextCompat
import space.manus.nacre.ime.NacreInputMethodService

/**
 * Manages voice input using Android SpeechRecognizer API.
 *
 * Features (Typeless-level quality):
 * - Continuous recognition: auto-restarts after each utterance
 * - Partial results displayed in real-time
 * - Auto punctuation insertion (。、？！)
 * - Bilingual support: ja-JP primary, auto-detects English
 * - Silence detection with configurable timeout
 */
class VoiceInputManager(private val service: NacreInputMethodService) {

    var isListening by mutableStateOf(false)
        private set
    var partialText by mutableStateOf("")
        private set
    var lastError by mutableStateOf("")
        private set

    private var speechRecognizer: SpeechRecognizer? = null
    private var continuousMode = false
    private var currentLanguage = "ja-JP"
    private var committedInSession = StringBuilder()

    fun isAvailable(): Boolean {
        return SpeechRecognizer.isRecognitionAvailable(service)
    }

    fun hasPermission(): Boolean {
        return ContextCompat.checkSelfPermission(
            service, Manifest.permission.RECORD_AUDIO,
        ) == PackageManager.PERMISSION_GRANTED
    }

    /**
     * Start continuous voice input. Keeps listening until explicitly stopped.
     */
    fun startListening(language: String = "ja-JP") {
        if (isListening) return
        if (service.inputEngine.isPasswordField) {
            Log.w(TAG, "Voice input disabled in password field")
            return
        }
        if (!isBatteryOk()) {
            Log.w(TAG, "Battery too low for voice input")
            lastError = "バッテリー残量不足"
            return
        }
        if (!hasPermission()) {
            Log.w(TAG, "RECORD_AUDIO permission not granted")
            lastError = "マイク権限がありません"
            return
        }
        if (!isAvailable()) {
            Log.w(TAG, "Speech recognition not available")
            lastError = "音声認識が利用できません"
            return
        }

        isListening = true
        partialText = ""
        lastError = ""
        continuousMode = true
        currentLanguage = language
        committedInSession.clear()

        startRecognizer()
    }

    private fun startRecognizer() {
        // SpeechRecognizer must be created and used on the main thread
        val mainHandler = android.os.Handler(android.os.Looper.getMainLooper())
        mainHandler.post {
            try {
                speechRecognizer?.destroy()
                speechRecognizer = null

                val recognizer = SpeechRecognizer.createSpeechRecognizer(service.applicationContext)
                if (recognizer == null) {
                    Log.e(TAG, "createSpeechRecognizer returned null")
                    lastError = "音声認識を初期化できません"
                    isListening = false
                    continuousMode = false
                    return@post
                }

                recognizer.setRecognitionListener(listener)
                speechRecognizer = recognizer

                val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                    putExtra(
                        RecognizerIntent.EXTRA_LANGUAGE_MODEL,
                        RecognizerIntent.LANGUAGE_MODEL_FREE_FORM,
                    )
                    putExtra(RecognizerIntent.EXTRA_LANGUAGE, currentLanguage)
                    putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
                    putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 3)
                    // Longer silence detection for continuous dictation
                    putExtra("android.speech.extra.SPEECH_INPUT_MINIMUM_LENGTH_MILLIS", 10000L)
                    putExtra("android.speech.extra.SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS", 2000L)
                    putExtra("android.speech.extra.SPEECH_INPUT_POSSIBLY_COMPLETE_SILENCE_LENGTH_MILLIS", 1500L)
                }

                recognizer.startListening(intent)
                Log.i(TAG, "SpeechRecognizer started (lang=$currentLanguage)")
            } catch (e: Exception) {
                Log.e(TAG, "Failed to start listening", e)
                lastError = "音声認識の開始に失敗: ${e.message}"
                isListening = false
                continuousMode = false
            }
        }
    }

    fun stopListening() {
        continuousMode = false
        try {
            speechRecognizer?.stopListening()
        } catch (_: Exception) {}
        isListening = false
        partialText = ""
    }

    fun cancel() {
        continuousMode = false
        try {
            speechRecognizer?.cancel()
        } catch (_: Exception) {}
        isListening = false
        partialText = ""
    }

    fun release() {
        cancel()
        speechRecognizer?.destroy()
        speechRecognizer = null
    }

    /**
     * Add punctuation based on context.
     * Japanese text gets 。, English gets period.
     */
    private fun addAutoPunctuation(text: String): String {
        if (text.isEmpty()) return text
        val lastChar = text.last()
        // Don't add punctuation if already has one
        if (lastChar in "。、！？.!?,") return text
        // Check if text is mostly Japanese
        val hasJapanese = text.any { it.code in 0x3000..0x9FFF || it.code in 0xFF00..0xFFEF }
        return if (hasJapanese) "$text。" else "$text."
    }

    private val listener = object : RecognitionListener {
        override fun onReadyForSpeech(params: Bundle?) {
            lastError = ""
        }
        override fun onBeginningOfSpeech() {}
        override fun onRmsChanged(rmsdB: Float) {}
        override fun onBufferReceived(buffer: ByteArray?) {}
        override fun onEndOfSpeech() {}

        override fun onError(error: Int) {
            val msg = when (error) {
                SpeechRecognizer.ERROR_NO_MATCH -> "聞き取れませんでした"
                SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> "音声入力タイムアウト"
                SpeechRecognizer.ERROR_AUDIO -> "オーディオエラー"
                SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> "マイク権限がありません"
                SpeechRecognizer.ERROR_NETWORK -> "ネットワークエラー"
                SpeechRecognizer.ERROR_NETWORK_TIMEOUT -> "ネットワークタイムアウト"
                SpeechRecognizer.ERROR_SERVER -> "サーバーエラー"
                else -> "エラー ($error)"
            }
            Log.w(TAG, "Recognition error: $msg (code=$error)")
            lastError = msg
            partialText = ""

            // In continuous mode, auto-restart on recoverable errors
            if (continuousMode && error in RECOVERABLE_ERRORS) {
                // Brief delay before restart to avoid rapid-fire errors
                android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
                    if (continuousMode && isBatteryOk()) {
                        startRecognizer()
                    } else {
                        isListening = false
                        continuousMode = false
                    }
                }, 300)
            } else {
                isListening = false
                continuousMode = false
            }
        }

        override fun onResults(results: Bundle?) {
            val alternatives = results
                ?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
            val text = alternatives?.firstOrNull() ?: ""

            if (text.isNotEmpty()) {
                val processed = addAutoPunctuation(text)
                service.currentInputConnection?.commitText(processed, 1)
                committedInSession.append(processed)
            }
            partialText = ""

            // Continuous mode: auto-restart for next utterance
            if (continuousMode && isBatteryOk()) {
                startRecognizer()
            } else {
                isListening = false
                continuousMode = false
            }
        }

        override fun onPartialResults(partialResults: Bundle?) {
            partialText = partialResults
                ?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                ?.firstOrNull() ?: ""
        }

        override fun onEvent(eventType: Int, params: Bundle?) {}
    }

    private fun isBatteryOk(): Boolean {
        val bm = service.getSystemService(Context.BATTERY_SERVICE) as? BatteryManager ?: return true
        val level = bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
        return level > BATTERY_THRESHOLD
    }

    companion object {
        private const val TAG = "VoiceInput"
        private const val BATTERY_THRESHOLD = 20
        private val RECOVERABLE_ERRORS = setOf(
            SpeechRecognizer.ERROR_NO_MATCH,
            SpeechRecognizer.ERROR_SPEECH_TIMEOUT,
            SpeechRecognizer.ERROR_NETWORK,
            SpeechRecognizer.ERROR_NETWORK_TIMEOUT,
            SpeechRecognizer.ERROR_SERVER,
        )
    }
}
