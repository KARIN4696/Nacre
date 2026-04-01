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
import kotlinx.coroutines.channels.Channel

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

    // Continuous recording state
    private var transcriptionChannel = Channel<FloatArray>(Channel.BUFFERED)
    private var recordingJob: Job? = null
    private var transcriptionJob: Job? = null
    private val textBuffer = StringBuilder()
    private val postProcessor = PostProcessor()
    private var continuousCallback: IWhisperCallback? = null

    private val binder = object : IWhisperService.Stub() {

        override fun isModelLoaded(): Boolean {
            return WhisperJni.isAvailable() && WhisperJni.isModelLoaded()
        }

        override fun isRecognizing(): Boolean {
            return this@WhisperService.isRecognizing
        }

        override fun loadModel(modelPath: String) {
            scope.launch {
                try {
                    Log.i(TAG, "Loading Whisper model: $modelPath")
                    val ok = WhisperJni.loadModel(modelPath)
                    Log.i(TAG, "Whisper model loaded: $ok (path=$modelPath)")
                } catch (e: Exception) {
                    Log.e(TAG, "Whisper model loading CRASHED", e)
                }
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
            // If continuous mode is active, finalize it
            if (recordingJob != null) {
                scope.launch {
                    recordingJob?.cancel()
                    recordingJob = null
                    transcriptionChannel.close()
                    transcriptionJob?.join()
                    transcriptionJob = null
                    this@WhisperService.isRecognizing = false
                    try { stopForeground(STOP_FOREGROUND_REMOVE) } catch (_: Exception) {}
                    continuousCallback?.onResult(textBuffer.toString())
                    continuousCallback = null
                }
                return
            }
            recordJob?.cancel()
            this@WhisperService.isRecognizing = false
        }

        override fun startContinuousRecognition(language: String, callback: IWhisperCallback) {
            // Guard against double-start
            if (this@WhisperService.isRecognizing) {
                try { callback.onError("Already recognizing") } catch (_: RemoteException) {}
                return
            }
            if (!WhisperJni.isModelLoaded()) {
                callback.onError("Model not loaded")
                return
            }

            // Start as foreground service for microphone access on Android 12+
            try {
                if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.S) {
                    val channelId = "whisper_recording"
                    val channel = android.app.NotificationChannel(
                        channelId, "Voice Input", android.app.NotificationManager.IMPORTANCE_LOW
                    )
                    val nm = getSystemService(android.app.NotificationManager::class.java)
                    nm.createNotificationChannel(channel)
                    val notification = android.app.Notification.Builder(this@WhisperService, channelId)
                        .setContentTitle("Nacre")
                        .setContentText("Listening...")
                        .setSmallIcon(android.R.drawable.ic_btn_speak_now)
                        .build()
                    startForeground(9999, notification,
                        android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE)
                }
            } catch (e: Exception) {
                Log.w(TAG, "Failed to start foreground service", e)
            }

            this@WhisperService.isRecognizing = true
            continuousCallback = callback
            textBuffer.clear()
            transcriptionChannel = Channel(Channel.UNLIMITED)

            // Recording coroutine
            recordingJob = scope.launch(Dispatchers.Default) {
                val bufferSize = android.media.AudioRecord.getMinBufferSize(
                    SAMPLE_RATE,
                    android.media.AudioFormat.CHANNEL_IN_MONO,
                    android.media.AudioFormat.ENCODING_PCM_FLOAT
                )
                val recorder = android.media.AudioRecord(
                    android.media.MediaRecorder.AudioSource.MIC,
                    SAMPLE_RATE,
                    android.media.AudioFormat.CHANNEL_IN_MONO,
                    android.media.AudioFormat.ENCODING_PCM_FLOAT,
                    bufferSize * 2
                )
                recorder.startRecording()

                val chunkSamples = SAMPLE_RATE * CHUNK_SIZE_MS / 1000
                val readBuffer = FloatArray(chunkSamples)
                val audioBuffer = mutableListOf<Float>()
                var silentChunks = 0
                val silentChunksThreshold = (CHUNK_SILENCE_THRESHOLD_SEC * 1000 / CHUNK_SIZE_MS).toInt()
                val maxChunks = CONTINUOUS_MAX_DURATION_SEC * 1000 / CHUNK_SIZE_MS
                var totalChunks = 0

                try {
                    while (isActive && totalChunks < maxChunks) {
                        val read = recorder.read(readBuffer, 0, chunkSamples, android.media.AudioRecord.READ_BLOCKING)
                        if (read < 0) {
                            Log.e(TAG, "AudioRecord.read() error: $read")
                            break
                        }
                        if (read == 0) continue
                        totalChunks++

                        var sum = 0.0
                        for (i in 0 until read) { sum += readBuffer[i] * readBuffer[i] }
                        val rms = Math.sqrt(sum / read).toFloat()

                        // Always accumulate audio (including silence — Whisper needs natural pauses)
                        for (i in 0 until read) { audioBuffer.add(readBuffer[i]) }

                        if (rms < VAD_RMS_THRESHOLD) {
                            silentChunks++
                            if (silentChunks >= silentChunksThreshold) {
                                if (audioBuffer.isNotEmpty()) {
                                    transcriptionChannel.send(audioBuffer.toFloatArray())
                                    audioBuffer.clear()
                                }
                                silentChunks = 0
                            }
                        } else {
                            silentChunks = 0
                        }
                    }
                    if (audioBuffer.isNotEmpty()) {
                        transcriptionChannel.send(audioBuffer.toFloatArray())
                    }
                } finally {
                    recorder.stop()
                    recorder.release()
                    transcriptionChannel.close()
                }
            }

            // Transcription coroutine
            transcriptionJob = scope.launch(Dispatchers.Default) {
                for (chunkAudio in transcriptionChannel) {
                    if (!isActive) break
                    try {
                        val rawText = WhisperJni.transcribe(chunkAudio, language)
                        if (rawText.isBlank()) continue
                        val result = postProcessor.process(rawText)
                        if (result.command != null) {
                            when (result.command) {
                                VoiceCommand.NewLine -> textBuffer.append("\n")
                                VoiceCommand.Period -> {
                                    if (textBuffer.isNotEmpty() && textBuffer.last() != '。' && textBuffer.last() != '.') {
                                        val hasJa = textBuffer.any { it.code in 0x3000..0x9FFF }
                                        textBuffer.append(if (hasJa) "。" else ".")
                                    }
                                }
                                VoiceCommand.Undo -> {
                                    val lastBreak = textBuffer.lastIndexOfAny(charArrayOf('。', '.', '\n'))
                                    if (lastBreak >= 0) textBuffer.delete(lastBreak, textBuffer.length)
                                    else textBuffer.clear()
                                }
                                VoiceCommand.Commit -> {
                                    continuousCallback?.onResult(textBuffer.toString())
                                    stopContinuousRecordingInternal()
                                    return@launch
                                }
                            }
                        } else if (result.text.isNotBlank()) {
                            textBuffer.append(result.text)
                        }
                        continuousCallback?.onPartialResult(textBuffer.toString())
                    } catch (e: Exception) {
                        android.util.Log.e("WhisperService", "Transcription error", e)
                    }
                }
            }
        }

        override fun cancelContinuousRecognition() {
            stopContinuousRecordingInternal()
            textBuffer.clear()
        }
    }

    private fun stopContinuousRecordingInternal() {
        recordingJob?.cancel()
        recordingJob = null
        transcriptionJob?.cancel()
        transcriptionJob = null
        isRecognizing = false
        try { stopForeground(STOP_FOREGROUND_REMOVE) } catch (_: Exception) {}
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
        stopContinuousRecordingInternal()
        scope.cancel()
        super.onDestroy()
    }

    companion object {
        private const val TAG = "WhisperService"
        private const val CONTINUOUS_MAX_DURATION_SEC = 360 // 6 minutes
        private const val CHUNK_SILENCE_THRESHOLD_SEC = 1.5f
        private const val VAD_RMS_THRESHOLD = 0.005f
        private const val SAMPLE_RATE = 16000
        private const val CHUNK_SIZE_MS = 250
    }
}
