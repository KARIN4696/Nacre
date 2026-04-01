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
 * Quality improvements over basic Whisper integration:
 * - Adaptive VAD using ambient noise calibration + zero-crossing rate
 * - Audio preprocessing (DC offset removal, pre-emphasis filter, normalization)
 * - Context priming via initial_prompt for cross-chunk coherence
 * - Hallucination detection and deduplication
 * - Minimum chunk duration enforcement
 * - Pre-padding audio before voice onset
 *
 * Falls back to Android SpeechRecognizer API if native library is unavailable.
 */
class WhisperService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    @Volatile
    private var isRecognizing = false
    @Volatile
    private var recordJob: Job? = null

    // Continuous recording state
    @Volatile
    private var transcriptionChannel = Channel<FloatArray>(Channel.UNLIMITED)
    @Volatile
    private var recordingJob: Job? = null
    @Volatile
    private var transcriptionJob: Job? = null
    @Volatile
    private var textBuffer = StringBuilder()
    private val postProcessor = PostProcessor()
    @Volatile
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
                    // Preprocess audio before transcription
                    val processed = preprocessAudio(audioData)
                    val result = WhisperJni.transcribe(processed, language)
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
            val rJob = recordingJob
            if (rJob != null) {
                // Capture references locally before clearing -- a new startContinuousRecognition()
                // could overwrite instance fields before the cleanup coroutine runs.
                val tJob = transcriptionJob
                val cb = continuousCallback
                // Capture a reference to the current textBuffer. A new session may
                // replace it (see startContinuousRecognition), so we hold our own ref.
                val buf = textBuffer

                // Clear synchronously so the next startContinuousRecognition() won't be
                // rejected with "Already recognizing" while async cleanup is in progress.
                recordingJob = null
                transcriptionJob = null
                continuousCallback = null
                this@WhisperService.isRecognizing = false

                scope.launch {
                    // Cancel recording -- its finally block sends remaining buffer and closes channel
                    rJob.cancel()
                    rJob.join()
                    // Wait for transcription to finish consuming all chunks
                    tJob?.join()
                    // Snapshot the text AFTER transcription has fully drained.
                    val finalText = buf.toString()
                    try { stopForeground(STOP_FOREGROUND_REMOVE) } catch (_: Exception) {}
                    try {
                        cb?.onResult(finalText)
                    } catch (_: RemoteException) {}
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
                try { callback.onError("Model not loaded") } catch (_: RemoteException) {}
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
            // Create a NEW StringBuilder rather than clearing the old one, so that
            // a concurrent stopRecognition() coroutine can still read the old buffer.
            textBuffer = StringBuilder()
            transcriptionChannel = Channel(Channel.UNLIMITED)

            // Recording coroutine with improved VAD and audio preprocessing
            recordingJob = scope.launch(Dispatchers.Default) {
                val bufferSize = AudioRecord.getMinBufferSize(
                    SAMPLE_RATE,
                    AudioFormat.CHANNEL_IN_MONO,
                    AudioFormat.ENCODING_PCM_FLOAT
                )
                val recorder = AudioRecord(
                    MediaRecorder.AudioSource.MIC,
                    SAMPLE_RATE,
                    AudioFormat.CHANNEL_IN_MONO,
                    AudioFormat.ENCODING_PCM_FLOAT,
                    bufferSize * 2
                )
                recorder.startRecording()

                val chunkSamples = SAMPLE_RATE * CHUNK_SIZE_MS / 1000
                val readBuffer = FloatArray(chunkSamples)
                val audioBuffer = mutableListOf<Float>()
                // Pre-padding ring buffer: keeps last PRE_PADDING_SAMPLES before voice onset
                val prePadBuffer = FloatArray(PRE_PADDING_SAMPLES)
                var prePadWriteIdx = 0
                var prePadFilled = false

                var silentChunks = 0
                val silentChunksThreshold = (CHUNK_SILENCE_THRESHOLD_SEC * 1000 / CHUNK_SIZE_MS).toInt()
                val maxChunks = CONTINUOUS_MAX_DURATION_SEC * 1000 / CHUNK_SIZE_MS
                var totalChunks = 0
                var voiceChunksInCurrentSegment = 0

                // Adaptive VAD: calibrate ambient noise from first few chunks
                var ambientNoiseRms = 0.0f
                var calibrationSamples = 0
                var calibrationSum = 0.0
                val calibrationChunks = (AMBIENT_CALIBRATION_SEC * 1000 / CHUNK_SIZE_MS).toInt()
                var isCalibrated = false

                var consecutiveErrors = 0
                try {
                    while (isActive && totalChunks < maxChunks) {
                        val read = recorder.read(readBuffer, 0, chunkSamples, AudioRecord.READ_BLOCKING)
                        if (read < 0) {
                            consecutiveErrors++
                            Log.e(TAG, "AudioRecord.read() error: $read (consecutive=$consecutiveErrors)")
                            if (consecutiveErrors >= 3) {
                                Log.e(TAG, "Too many consecutive AudioRecord errors, stopping")
                                break
                            }
                            continue
                        }
                        consecutiveErrors = 0
                        if (read == 0) continue
                        totalChunks++

                        // Compute RMS energy
                        var sum = 0.0
                        for (i in 0 until read) { sum += readBuffer[i] * readBuffer[i] }
                        val rms = Math.sqrt(sum / read).toFloat()

                        // Compute zero-crossing rate (ZCR) for better VAD
                        var zeroCrossings = 0
                        for (i in 1 until read) {
                            if ((readBuffer[i] >= 0 && readBuffer[i - 1] < 0) ||
                                (readBuffer[i] < 0 && readBuffer[i - 1] >= 0)) {
                                zeroCrossings++
                            }
                        }
                        val zcr = zeroCrossings.toFloat() / read

                        // Ambient noise calibration (first N chunks)
                        if (!isCalibrated) {
                            calibrationSum += rms
                            calibrationSamples++
                            if (calibrationSamples >= calibrationChunks) {
                                ambientNoiseRms = (calibrationSum / calibrationSamples).toFloat()
                                isCalibrated = true
                                Log.i(TAG, "Ambient noise calibrated: RMS=$ambientNoiseRms")
                            }
                        }

                        // Adaptive VAD threshold: ambient noise * multiplier, with floor
                        val vadThreshold = if (isCalibrated) {
                            maxOf(ambientNoiseRms * ADAPTIVE_VAD_MULTIPLIER, VAD_RMS_FLOOR)
                        } else {
                            VAD_RMS_FLOOR
                        }

                        // Voice detection: RMS above threshold AND ZCR in speech range
                        // Speech typically has ZCR between 0.01 and 0.25
                        // Pure noise tends to have very high or very low ZCR
                        val isVoice = rms > vadThreshold && zcr > ZCR_MIN && zcr < ZCR_MAX

                        if (!isVoice) {
                            // Update pre-padding ring buffer during silence
                            for (i in 0 until read) {
                                prePadBuffer[prePadWriteIdx] = readBuffer[i]
                                prePadWriteIdx = (prePadWriteIdx + 1) % PRE_PADDING_SAMPLES
                                if (prePadWriteIdx == 0) prePadFilled = true
                            }

                            silentChunks++
                            if (silentChunks >= silentChunksThreshold) {
                                if (audioBuffer.isNotEmpty() &&
                                    voiceChunksInCurrentSegment >= MIN_VOICE_CHUNKS) {
                                    // Chunk meets minimum duration -- send for transcription
                                    transcriptionChannel.send(audioBuffer.toFloatArray())
                                    audioBuffer.clear()
                                    voiceChunksInCurrentSegment = 0
                                } else if (audioBuffer.isNotEmpty()) {
                                    // Too short -- discard (likely noise burst)
                                    Log.d(TAG, "Discarding short segment: ${voiceChunksInCurrentSegment} voice chunks")
                                    audioBuffer.clear()
                                    voiceChunksInCurrentSegment = 0
                                }
                                silentChunks = 0
                            }
                        } else {
                            // Voice detected
                            if (audioBuffer.isEmpty()) {
                                // Voice onset -- inject pre-padding for better recognition
                                val padLen = if (prePadFilled) PRE_PADDING_SAMPLES
                                             else prePadWriteIdx
                                if (padLen > 0) {
                                    val startIdx = if (prePadFilled) prePadWriteIdx else 0
                                    for (i in 0 until padLen) {
                                        audioBuffer.add(prePadBuffer[(startIdx + i) % PRE_PADDING_SAMPLES])
                                    }
                                }
                            }
                            for (i in 0 until read) { audioBuffer.add(readBuffer[i]) }
                            voiceChunksInCurrentSegment++
                            silentChunks = 0
                        }
                    }
                } finally {
                    // Send remaining buffered audio before closing (even on cancellation)
                    if (audioBuffer.isNotEmpty() && voiceChunksInCurrentSegment >= MIN_VOICE_CHUNKS) {
                        withContext(NonCancellable) {
                            try {
                                transcriptionChannel.send(audioBuffer.toFloatArray())
                            } catch (e: Exception) {
                                Log.w(TAG, "Failed to send remaining audio buffer", e)
                            }
                        }
                    }
                    recorder.stop()
                    recorder.release()
                    transcriptionChannel.close()
                }
            }

            // Transcription coroutine with context priming and hallucination detection
            transcriptionJob = scope.launch(Dispatchers.Default) {
                var lastTranscribedText = ""

                for (chunkAudio in transcriptionChannel) {
                    if (!isActive) break
                    try {
                        // Preprocess audio: DC removal + pre-emphasis + normalization
                        val processed = preprocessAudio(chunkAudio)

                        // Check minimum audio energy -- skip near-silent chunks
                        val energy = computeRms(processed)
                        if (energy < MIN_TRANSCRIPTION_ENERGY) {
                            Log.d(TAG, "Skipping low-energy chunk: RMS=$energy")
                            continue
                        }

                        // Context priming: pass last transcribed text as initial_prompt
                        val contextPrompt = textBuffer.toString().takeLast(CONTEXT_PROMPT_MAX_CHARS)
                        val rawText = if (contextPrompt.isNotEmpty()) {
                            WhisperJni.transcribeWithContext(processed, language, contextPrompt)
                        } else {
                            WhisperJni.transcribe(processed, language)
                        }

                        if (rawText.isBlank()) continue

                        // Hallucination detection: check for repeated text
                        val trimmedRaw = rawText.trim()
                        if (isHallucination(trimmedRaw, lastTranscribedText)) {
                            Log.w(TAG, "Hallucination detected, skipping: '$trimmedRaw'")
                            continue
                        }
                        lastTranscribedText = trimmedRaw

                        val result = postProcessor.process(rawText)
                        // Append processed text first (trailing command case has both text + command)
                        if (result.text.isNotBlank()) {
                            textBuffer.append(result.text)
                        }
                        // Then handle command
                        if (result.command != null) {
                            when (result.command) {
                                VoiceCommand.NewLine -> textBuffer.append("\n")
                                VoiceCommand.Period -> {
                                    if (textBuffer.isNotEmpty() && textBuffer.last() != '\u3002' && textBuffer.last() != '.') {
                                        val hasJa = textBuffer.any { it.code in 0x3000..0x9FFF }
                                        textBuffer.append(if (hasJa) "\u3002" else ".")
                                    }
                                }
                                VoiceCommand.Undo -> {
                                    val lastBreak = textBuffer.lastIndexOfAny(charArrayOf('\u3002', '.', '\n'))
                                    if (lastBreak >= 0) textBuffer.delete(lastBreak, textBuffer.length)
                                    else textBuffer.clear()
                                }
                                VoiceCommand.Commit -> {
                                    try {
                                        continuousCallback?.onResult(textBuffer.toString())
                                    } catch (_: RemoteException) {
                                        Log.w(TAG, "Commit callback failed (client dead?)")
                                    }
                                    stopContinuousRecordingInternal()
                                    return@launch
                                }
                                VoiceCommand.Comma -> {
                                    if (textBuffer.isNotEmpty()) {
                                        val hasJa = textBuffer.any { it.code in 0x3000..0x9FFF }
                                        textBuffer.append(if (hasJa) "\u3001" else ",")
                                    }
                                }
                                VoiceCommand.ClearAll -> textBuffer.clear()
                                VoiceCommand.Space -> textBuffer.append(" ")
                                VoiceCommand.OpenParen -> {
                                    val hasJa = textBuffer.any { it.code in 0x3000..0x9FFF }
                                    textBuffer.append(if (hasJa) "\uFF08" else "(")
                                }
                                VoiceCommand.CloseParen -> {
                                    val hasJa = textBuffer.any { it.code in 0x3000..0x9FFF }
                                    textBuffer.append(if (hasJa) "\uFF09" else ")")
                                }
                            }
                        }
                        try {
                            continuousCallback?.onPartialResult(textBuffer.toString())
                        } catch (_: RemoteException) {
                            // Client died -- stop recording to avoid wasting resources
                            Log.w(TAG, "Partial result callback failed (client dead?), stopping")
                            stopContinuousRecordingInternal()
                            return@launch
                        }
                    } catch (e: Exception) {
                        Log.e(TAG, "Transcription error", e)
                    }
                }
            }
        }

        override fun cancelContinuousRecognition() {
            stopContinuousRecordingInternal()
            textBuffer.clear()
        }
    }

    // ---- Audio preprocessing ----

    /**
     * Preprocess audio for better Whisper recognition:
     * 1. DC offset removal (high-pass at ~1Hz)
     * 2. Pre-emphasis filter (boost high frequencies for clearer consonants)
     * 3. Amplitude normalization (target peak = 0.9)
     */
    private fun preprocessAudio(audio: FloatArray): FloatArray {
        if (audio.isEmpty()) return audio
        val out = FloatArray(audio.size)

        // Step 1: DC offset removal -- subtract mean
        var mean = 0.0
        for (s in audio) mean += s
        mean /= audio.size

        // Step 2: Pre-emphasis filter (y[n] = x[n] - alpha * x[n-1])
        // alpha = 0.97 is standard for speech processing
        out[0] = (audio[0] - mean).toFloat()
        for (i in 1 until audio.size) {
            out[i] = ((audio[i] - mean) - PRE_EMPHASIS_ALPHA * (audio[i - 1] - mean)).toFloat()
        }

        // Step 3: Normalize to target peak amplitude
        var maxAbs = 0.0f
        for (s in out) {
            val abs = kotlin.math.abs(s)
            if (abs > maxAbs) maxAbs = abs
        }
        if (maxAbs > 0.001f) {
            val scale = NORMALIZATION_TARGET / maxAbs
            // Only amplify if quieter than target; don't reduce already-loud audio
            if (scale > 1.0f) {
                for (i in out.indices) out[i] *= scale
            }
        }

        return out
    }

    /**
     * Compute RMS energy of audio samples.
     */
    private fun computeRms(audio: FloatArray): Float {
        if (audio.isEmpty()) return 0.0f
        var sum = 0.0
        for (s in audio) sum += s * s
        return Math.sqrt(sum / audio.size).toFloat()
    }

    // ---- Hallucination detection ----

    /**
     * Detect Whisper hallucinations:
     * - Repeated text identical to previous chunk
     * - Common hallucination patterns (repeated phrases, phantom text)
     * - Very short repeated strings that are likely artifacts
     */
    private fun isHallucination(current: String, previous: String): Boolean {
        if (current.isBlank()) return true

        // Exact duplicate of previous chunk
        if (previous.isNotBlank() && current == previous) {
            return true
        }

        // Check for internal repetition (e.g., "thank you thank you thank you")
        // Split into words/characters and check if any token repeats > 3 times consecutively
        val hasJapanese = current.any { it.code in 0x3000..0x9FFF }
        if (hasJapanese) {
            // For Japanese: check character-level repetition patterns
            // e.g., "ありがとうありがとうありがとう" -> repeated phrase
            val len = current.length
            if (len >= 6) {
                // Check if text is a repeated substring
                for (patLen in 2..len / 3) {
                    val pattern = current.substring(0, patLen)
                    val expectedRepeats = len / patLen
                    if (expectedRepeats >= 3 && pattern.repeat(expectedRepeats) == current.substring(0, patLen * expectedRepeats)) {
                        return true
                    }
                }
            }
        } else {
            // For English: word-level repetition check
            val words = current.lowercase().split("\\s+".toRegex()).filter { it.isNotBlank() }
            if (words.size >= 3) {
                var maxConsecutive = 1
                var consecutive = 1
                for (i in 1 until words.size) {
                    if (words[i] == words[i - 1]) {
                        consecutive++
                        if (consecutive > maxConsecutive) maxConsecutive = consecutive
                    } else {
                        consecutive = 1
                    }
                }
                if (maxConsecutive >= 3) return true
            }
        }

        // Common Whisper hallucination patterns
        val lowerCurrent = current.lowercase().trim()
        for (pattern in HALLUCINATION_PATTERNS) {
            if (lowerCurrent.contains(pattern)) return true
        }

        return false
    }

    // ---- Existing methods ----

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
        private const val SAMPLE_RATE = 16000
        private const val CHUNK_SIZE_MS = 250

        // ---- VAD tuning ----
        // Floor RMS threshold (used before calibration and as absolute minimum)
        private const val VAD_RMS_FLOOR = 0.003f
        // Adaptive VAD: threshold = ambient_noise_rms * multiplier
        private const val ADAPTIVE_VAD_MULTIPLIER = 2.5f
        // Ambient noise calibration duration (seconds)
        private const val AMBIENT_CALIBRATION_SEC = 0.5f
        // Zero-crossing rate bounds for speech detection
        private const val ZCR_MIN = 0.01f  // Below this = DC/very low freq noise
        private const val ZCR_MAX = 0.35f  // Above this = high-freq noise/hiss

        // ---- Chunk quality ----
        // Minimum voice chunks before a segment is worth transcribing
        // At 250ms per chunk, 2 chunks = 0.5s minimum speech
        private const val MIN_VOICE_CHUNKS = 2
        // Pre-padding: 0.3s of audio before voice onset (improves word-initial recognition)
        private const val PRE_PADDING_SAMPLES = (SAMPLE_RATE * 0.3).toInt() // 4800 samples
        // Minimum RMS energy to attempt transcription
        private const val MIN_TRANSCRIPTION_ENERGY = 0.002f

        // ---- Audio preprocessing ----
        // Pre-emphasis coefficient (standard for speech: 0.97)
        private const val PRE_EMPHASIS_ALPHA = 0.97
        // Normalization target peak amplitude
        private const val NORMALIZATION_TARGET = 0.9f

        // ---- Context priming ----
        // Maximum characters from previous text to use as initial_prompt
        private const val CONTEXT_PROMPT_MAX_CHARS = 200

        // ---- Hallucination detection ----
        // Common Whisper hallucination phrases (lowercase)
        private val HALLUCINATION_PATTERNS = listOf(
            "thank you for watching",
            "thanks for watching",
            "please subscribe",
            "like and subscribe",
            "see you next time",
            "goodbye",
            "the end",
            "\u3054\u8996\u8074\u3042\u308a\u304c\u3068\u3046\u3054\u3056\u3044\u307e\u3057\u305f",  // ご視聴ありがとうございました
            "\u304a\u758b\u3057\u307f\u306b",  // おたのしみに / お楽しみに
            "\u30c1\u30e3\u30f3\u30cd\u30eb\u767b\u9332",  // チャンネル登録
            "\u6b21\u56de\u3082",  // 次回も
        )
    }
}
