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
 * Runs directly in the IME process — no separate service needed.
 */
class VoiceInputManager(private val service: NacreInputMethodService) {

    var isListening by mutableStateOf(false)
        private set
    var partialText by mutableStateOf("")
        private set

    private var speechRecognizer: SpeechRecognizer? = null

    fun isAvailable(): Boolean {
        return SpeechRecognizer.isRecognitionAvailable(service)
    }

    fun hasPermission(): Boolean {
        return ContextCompat.checkSelfPermission(
            service, Manifest.permission.RECORD_AUDIO,
        ) == PackageManager.PERMISSION_GRANTED
    }

    fun startListening(language: String = "ja-JP") {
        if (isListening) return
        // SPEC: disable AI/voice input in password fields
        if (service.inputEngine.isPasswordField) {
            Log.w(TAG, "Voice input disabled in password field")
            return
        }
        // SPEC: battery 20% threshold — auto-stop AI features
        if (!isBatteryOk()) {
            Log.w(TAG, "Battery too low for voice input")
            return
        }
        if (!hasPermission()) {
            Log.w(TAG, "RECORD_AUDIO permission not granted")
            return
        }
        if (!isAvailable()) {
            Log.w(TAG, "Speech recognition not available")
            return
        }

        isListening = true
        partialText = ""

        speechRecognizer?.destroy()
        speechRecognizer = SpeechRecognizer.createSpeechRecognizer(service).apply {
            setRecognitionListener(listener)
        }

        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(
                RecognizerIntent.EXTRA_LANGUAGE_MODEL,
                RecognizerIntent.LANGUAGE_MODEL_FREE_FORM,
            )
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, language)
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
            putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, true)
        }

        try {
            speechRecognizer?.startListening(intent)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start listening", e)
            isListening = false
        }
    }

    fun stopListening() {
        try {
            speechRecognizer?.stopListening()
        } catch (_: Exception) {}
        isListening = false
    }

    fun cancel() {
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

    private val listener = object : RecognitionListener {
        override fun onReadyForSpeech(params: Bundle?) {}
        override fun onBeginningOfSpeech() {}
        override fun onRmsChanged(rmsdB: Float) {}
        override fun onBufferReceived(buffer: ByteArray?) {}
        override fun onEndOfSpeech() {}

        override fun onError(error: Int) {
            isListening = false
            partialText = ""
            val msg = when (error) {
                SpeechRecognizer.ERROR_NO_MATCH -> "No speech detected"
                SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> "No speech input"
                SpeechRecognizer.ERROR_AUDIO -> "Audio error"
                SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> "No mic permission"
                else -> "Error ($error)"
            }
            Log.w(TAG, "Recognition error: $msg")
        }

        override fun onResults(results: Bundle?) {
            isListening = false
            val text = results
                ?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                ?.firstOrNull() ?: ""
            if (text.isNotEmpty()) {
                service.currentInputConnection?.commitText(text, 1)
            }
            partialText = ""
        }

        override fun onPartialResults(partialResults: Bundle?) {
            partialText = partialResults
                ?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                ?.firstOrNull() ?: ""
        }

        override fun onEvent(eventType: Int, params: Bundle?) {}
    }

    // SPEC: battery 20% threshold for AI features
    private fun isBatteryOk(): Boolean {
        val bm = service.getSystemService(Context.BATTERY_SERVICE) as? BatteryManager ?: return true
        val level = bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
        return level > BATTERY_THRESHOLD
    }

    companion object {
        private const val TAG = "VoiceInput"
        private const val BATTERY_THRESHOLD = 20
    }
}
