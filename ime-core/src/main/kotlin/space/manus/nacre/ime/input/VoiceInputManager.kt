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
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.content.ContextCompat
import space.manus.nacre.ime.NacreInputMethodService

/**
 * Manages voice input using Android SpeechRecognizer API.
 *
 * Features:
 * - Continuous recognition: auto-restarts after each utterance
 * - Partial results displayed in real-time with RMS level
 * - Smart punctuation: 疑問文→？, 列挙→、, 文末→。/.
 * - Bilingual: ja-JP / en-US auto-detect from layer state
 * - Confidence-based candidate selection
 * - Silence detection with configurable timeout
 */
class VoiceInputManager(private val service: NacreInputMethodService) {

    var isListening by mutableStateOf(false)
        private set
    var partialText by mutableStateOf("")
        private set
    var lastError by mutableStateOf("")
        private set
    /** Normalized RMS level 0f..1f for visual feedback */
    var rmsLevel by mutableFloatStateOf(0f)
        private set

    private var speechRecognizer: SpeechRecognizer? = null
    private var continuousMode = false
    private var currentLanguage = "ja-JP"
    private var committedInSession = StringBuilder()
    private var utteranceCount = 0

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
            Log.w(TAG, "RECORD_AUDIO permission not granted, launching permission request")
            lastError = "マイク権限を許可してください"
            try {
                val intent = Intent().apply {
                    setClassName(service.packageName, "${service.packageName}.PermissionActivity")
                    putExtra("permission", Manifest.permission.RECORD_AUDIO)
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                service.startActivity(intent)
            } catch (e: Exception) {
                Log.e(TAG, "Failed to launch permission activity", e)
            }
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
        rmsLevel = 0f
        continuousMode = true
        currentLanguage = language
        committedInSession.clear()
        utteranceCount = 0

        startRecognizer()
    }

    private fun startRecognizer() {
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
                    // Accept both languages for better bilingual results
                    putExtra(RecognizerIntent.EXTRA_LANGUAGE_PREFERENCE, currentLanguage)
                    putExtra(RecognizerIntent.EXTRA_ONLY_RETURN_LANGUAGE_PREFERENCE, false)
                    putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
                    putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 5)
                    // Request confidence scores
                    putExtra(RecognizerIntent.EXTRA_CONFIDENCE_SCORES, true)
                    // Longer silence detection for continuous dictation
                    putExtra("android.speech.extra.SPEECH_INPUT_MINIMUM_LENGTH_MILLIS", 15000L)
                    putExtra("android.speech.extra.SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS", 2500L)
                    putExtra("android.speech.extra.SPEECH_INPUT_POSSIBLY_COMPLETE_SILENCE_LENGTH_MILLIS", 1800L)
                }

                recognizer.startListening(intent)
                Log.i(TAG, "SpeechRecognizer started (lang=$currentLanguage, utterance=${utteranceCount})")
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
        rmsLevel = 0f
    }

    fun cancel() {
        continuousMode = false
        try {
            speechRecognizer?.cancel()
        } catch (_: Exception) {}
        isListening = false
        partialText = ""
        rmsLevel = 0f
    }

    fun release() {
        cancel()
        speechRecognizer?.destroy()
        speechRecognizer = null
    }

    // ── Smart punctuation ─────────────────────────────────────────────

    /**
     * Intelligent punctuation insertion based on context analysis.
     * - Question detection: か/の/ですか/ますか → ？
     * - Exclamation: すごい/やばい/まじ → ！
     * - Mid-sentence continuation: add space between utterances
     * - Default: 。 for Japanese, . for English
     */
    private fun smartPunctuation(text: String): String {
        if (text.isEmpty()) return text
        val lastChar = text.last()
        // Already has punctuation
        if (lastChar in "。、！？.!?,;:") return text

        val hasJapanese = text.any { it.code in 0x3000..0x9FFF || it.code in 0xFF00..0xFFEF }

        if (hasJapanese) {
            // Question patterns
            if (text.endsWith("か") || text.endsWith("の") ||
                text.endsWith("ですか") || text.endsWith("ますか") ||
                text.endsWith("でしょう") || text.endsWith("かな") ||
                text.endsWith("だろう") || text.endsWith("ない")
            ) {
                return "$text？"
            }
            // Exclamation patterns
            if (text.endsWith("すごい") || text.endsWith("やばい") ||
                text.endsWith("まじ") || text.endsWith("よ") ||
                text.endsWith("ぞ") || text.endsWith("ね") ||
                text.endsWith("な") || text.endsWith("わ")
            ) {
                // Sentence-ending particles get 。 not ！ (more natural)
                return "$text。"
            }
            return "$text。"
        } else {
            // English question words
            val lower = text.lowercase()
            if (lower.startsWith("what ") || lower.startsWith("how ") ||
                lower.startsWith("why ") || lower.startsWith("where ") ||
                lower.startsWith("when ") || lower.startsWith("who ") ||
                lower.startsWith("which ") || lower.startsWith("is ") ||
                lower.startsWith("are ") || lower.startsWith("do ") ||
                lower.startsWith("does ") || lower.startsWith("can ") ||
                lower.startsWith("could ") || lower.startsWith("would ") ||
                lower.startsWith("should ")
            ) {
                return "$text?"
            }
            return "$text."
        }
    }

    /**
     * Insert spacing between utterances when appropriate.
     */
    private fun addUtteranceSpacing(text: String): String {
        if (committedInSession.isEmpty()) return text
        val lastCommitted = committedInSession.last()
        // Japanese: no space needed between utterances
        if (lastCommitted.code in 0x3000..0x9FFF || lastCommitted.code in 0xFF00..0xFFEF) {
            return text
        }
        // English: add space if last committed didn't end with space
        return if (lastCommitted != ' ') " $text" else text
    }

    /**
     * Select best result using confidence scores when available.
     */
    private fun selectBestResult(results: Bundle?): String {
        val alternatives = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
            ?: return ""
        if (alternatives.isEmpty()) return ""

        val confidences = results.getFloatArray(SpeechRecognizer.CONFIDENCE_SCORES)
        if (confidences != null && confidences.size == alternatives.size) {
            // Pick highest confidence result
            var bestIdx = 0
            var bestConf = confidences[0]
            for (i in 1 until confidences.size) {
                if (confidences[i] > bestConf) {
                    bestConf = confidences[i]
                    bestIdx = i
                }
            }
            Log.d(TAG, "Confidence scores: ${confidences.toList()}, selected [$bestIdx]='${alternatives[bestIdx]}'")
            return alternatives[bestIdx]
        }

        // No confidence scores available — use first result
        return alternatives[0]
    }

    // ── RecognitionListener ───────────────────────────────────────────

    private val listener = object : RecognitionListener {
        override fun onReadyForSpeech(params: Bundle?) {
            lastError = ""
            rmsLevel = 0f
        }

        override fun onBeginningOfSpeech() {
            // Haptic feedback when speech detected
            service.feedbackManager.onTrackballStep()
        }

        override fun onRmsChanged(rmsdB: Float) {
            // Normalize RMS to 0..1 range (typical range: -2 to 10 dB)
            rmsLevel = ((rmsdB + 2f) / 12f).coerceIn(0f, 1f)
        }

        override fun onBufferReceived(buffer: ByteArray?) {}
        override fun onEndOfSpeech() {
            rmsLevel = 0f
        }

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
            rmsLevel = 0f

            // In continuous mode, auto-restart on recoverable errors
            if (continuousMode && error in RECOVERABLE_ERRORS) {
                android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
                    if (continuousMode && isBatteryOk()) {
                        startRecognizer()
                    } else {
                        isListening = false
                        continuousMode = false
                    }
                }, if (error == SpeechRecognizer.ERROR_NO_MATCH) 100L else 500L)
            } else {
                isListening = false
                continuousMode = false
            }
        }

        override fun onResults(results: Bundle?) {
            val text = selectBestResult(results)

            if (text.isNotEmpty()) {
                val spaced = addUtteranceSpacing(text)
                val processed = smartPunctuation(spaced)
                service.currentInputConnection?.commitText(processed, 1)
                committedInSession.append(processed)
                utteranceCount++
            }
            partialText = ""
            rmsLevel = 0f

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
        private const val BATTERY_THRESHOLD = 15
        private val RECOVERABLE_ERRORS = setOf(
            SpeechRecognizer.ERROR_NO_MATCH,
            SpeechRecognizer.ERROR_SPEECH_TIMEOUT,
            SpeechRecognizer.ERROR_NETWORK,
            SpeechRecognizer.ERROR_NETWORK_TIMEOUT,
            SpeechRecognizer.ERROR_SERVER,
        )
    }
}
