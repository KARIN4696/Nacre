package space.manus.nacre.ai

import android.app.Service
import android.content.Intent
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.IBinder
import android.os.RemoteException
import android.util.Log
import kotlinx.coroutines.*

/**
 * Whisper speech-to-text service.
 *
 * Runs in a separate process (android:process=":whisper") for JNI crash isolation.
 * Uses whisper.cpp via JNI for fully offline speech recognition.
 *
 * Falls back to Android SpeechRecognizer API if native library is unavailable.
 */
class WhisperService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    @Volatile
    private var isRecognizing = false
    private var recordJob: Job? = null

    private val binder = object : IWhisperService.Stub() {

        override fun isModelLoaded(): Boolean {
            return WhisperJni.isAvailable() && WhisperJni.isModelLoaded()
        }

        override fun isRecognizing(): Boolean {
            return this@WhisperService.isRecognizing
        }

        override fun loadModel(modelPath: String) {
            scope.launch {
                Log.i(TAG, "Loading Whisper model: $modelPath")
                val ok = WhisperJni.loadModel(modelPath)
                Log.i(TAG, "Whisper model loaded: $ok")
            }
        }

        override fun unloadModel() {
            WhisperJni.unloadModel()
            Log.i(TAG, "Whisper model unloaded")
        }

        override fun startRecognition(language: String, callback: IWhisperCallback?) {
            if (this@WhisperService.isRecognizing) {
                try { callback?.onError("Already recognizing") } catch (_: RemoteException) {}
                return
            }
            if (!WhisperJni.isModelLoaded()) {
                // Fallback: try Android SpeechRecognizer
                startFallbackRecognition(language, callback)
                return
            }
            this@WhisperService.isRecognizing = true
            recordJob = scope.launch {
                try {
                    val audioData = recordAudio()
                    try { callback?.onPartialResult("Transcribing...") } catch (_: RemoteException) {}
                    val result = WhisperJni.transcribe(audioData, language)
                    withContext(Dispatchers.Main) {
                        try { callback?.onResult(result) } catch (_: RemoteException) {}
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Recognition error", e)
                    withContext(Dispatchers.Main) {
                        try { callback?.onError(e.message ?: "Recognition failed") } catch (_: RemoteException) {}
                    }
                } finally {
                    this@WhisperService.isRecognizing = false
                }
            }
        }

        override fun stopRecognition() {
            recordJob?.cancel()
            this@WhisperService.isRecognizing = false
        }
    }

    override fun onBind(intent: Intent?): IBinder = binder

    override fun onCreate() {
        super.onCreate()
        Log.d(TAG, "WhisperService created (process=${android.os.Process.myPid()})")
    }

    /**
     * Record up to 30 seconds of audio at 16kHz mono for Whisper.
     * Uses VAD (voice activity detection) to stop when silence exceeds 5 seconds.
     */
    private fun recordAudio(): FloatArray {
        val sampleRate = 16000
        val maxDurationSec = 30
        val silenceThresholdSec = 5 // Stop after 5s of silence
        val silenceThreshold = 0.005f // RMS below this = silence
        val chunkSamples = sampleRate / 4 // 250ms chunks for VAD

        val bufferSize = maxOf(
            AudioRecord.getMinBufferSize(
                sampleRate,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_FLOAT,
            ),
            sampleRate * maxDurationSec * 4,
        )

        val recorder = AudioRecord(
            MediaRecorder.AudioSource.MIC,
            sampleRate,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_FLOAT,
            bufferSize,
        )

        val samples = FloatArray(sampleRate * maxDurationSec)
        var offset = 0
        var silentChunks = 0
        val maxSilentChunks = silenceThresholdSec * 4 // 5s * 4 chunks/s = 20 chunks
        var hasVoice = false

        try {
            recorder.startRecording()
            while (offset < samples.size && isRecognizing) {
                val toRead = minOf(chunkSamples, samples.size - offset)
                val read = recorder.read(samples, offset, toRead, AudioRecord.READ_BLOCKING)
                if (read <= 0) break
                offset += read

                // VAD: compute RMS of this chunk
                var sumSq = 0f
                for (i in (offset - read) until offset) {
                    sumSq += samples[i] * samples[i]
                }
                val rms = kotlin.math.sqrt(sumSq / read)

                if (rms < silenceThreshold) {
                    silentChunks++
                    // Only stop on silence AFTER we've heard some voice
                    if (hasVoice && silentChunks >= maxSilentChunks) break
                } else {
                    hasVoice = true
                    silentChunks = 0
                }
            }
        } finally {
            recorder.stop()
            recorder.release()
        }

        return if (offset < samples.size) samples.copyOf(offset) else samples
    }

    /**
     * Fallback to Android SpeechRecognizer when native Whisper is unavailable.
     */
    private fun startFallbackRecognition(language: String, callback: IWhisperCallback?) {
        if (!android.speech.SpeechRecognizer.isRecognitionAvailable(this)) {
            try { callback?.onError("Speech recognition not available") } catch (_: RemoteException) {}
            return
        }
        isRecognizing = true
        scope.launch(Dispatchers.Main) {
            try {
                val recognizer = android.speech.SpeechRecognizer.createSpeechRecognizer(this@WhisperService)
                recognizer.setRecognitionListener(object : android.speech.RecognitionListener {
                    override fun onResults(results: android.os.Bundle?) {
                        isRecognizing = false
                        val text = results?.getStringArrayList(
                            android.speech.SpeechRecognizer.RESULTS_RECOGNITION,
                        )?.firstOrNull() ?: ""
                        try { callback?.onResult(text) } catch (_: RemoteException) {}
                        recognizer.destroy()
                    }
                    override fun onPartialResults(partialResults: android.os.Bundle?) {
                        val text = partialResults?.getStringArrayList(
                            android.speech.SpeechRecognizer.RESULTS_RECOGNITION,
                        )?.firstOrNull() ?: return
                        try { callback?.onPartialResult(text) } catch (_: RemoteException) {}
                    }
                    override fun onError(error: Int) {
                        isRecognizing = false
                        val msg = "SpeechRecognizer error: $error"
                        try { callback?.onError(msg) } catch (_: RemoteException) {}
                        recognizer.destroy()
                    }
                    override fun onReadyForSpeech(params: android.os.Bundle?) {}
                    override fun onBeginningOfSpeech() {}
                    override fun onRmsChanged(rmsdB: Float) {}
                    override fun onBufferReceived(buffer: ByteArray?) {}
                    override fun onEndOfSpeech() {}
                    override fun onEvent(eventType: Int, params: android.os.Bundle?) {}
                })
                val intent = android.content.Intent(android.speech.RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                    putExtra(android.speech.RecognizerIntent.EXTRA_LANGUAGE_MODEL,
                        android.speech.RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                    putExtra(android.speech.RecognizerIntent.EXTRA_LANGUAGE, language)
                    putExtra(android.speech.RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
                    putExtra(android.speech.RecognizerIntent.EXTRA_PREFER_OFFLINE, true)
                }
                recognizer.startListening(intent)
            } catch (e: Exception) {
                isRecognizing = false
                try { callback?.onError(e.message ?: "Fallback recognition failed") } catch (_: RemoteException) {}
            }
        }
    }

    override fun onDestroy() {
        recordJob?.cancel()
        scope.cancel()
        super.onDestroy()
    }

    companion object {
        private const val TAG = "WhisperService"
    }
}
