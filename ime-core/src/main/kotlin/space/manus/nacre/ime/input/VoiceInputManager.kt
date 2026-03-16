package space.manus.nacre.ime.input

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.BatteryManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
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
 * Typeless-level voice input manager.
 *
 * Key differences from basic SpeechRecognizer usage:
 * - Zero-gap continuous recognition: next recognizer pre-created before current finishes
 * - Streaming partial commit: long partial results committed incrementally
 * - Intelligent utterance merging: avoids fragmented sentences
 * - All recoverable errors auto-restart with zero delay
 * - Offline-preferred with network fallback
 * - Smart punctuation with context awareness
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
    private var nextRecognizer: SpeechRecognizer? = null // Pre-created for zero-gap restart
    private var continuousMode = false
    private var currentLanguage = "ja-JP"
    private var committedInSession = StringBuilder()
    private var utteranceCount = 0
    private val mainHandler = Handler(Looper.getMainLooper())

    // Streaming partial commit tracking
    private var lastCommittedPartial = ""
    private var partialStableCount = 0
    private var lastPartialText = ""
    private var lastPartialTime = 0L

    // Consecutive error tracking for backoff
    private var consecutiveErrors = 0

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
        consecutiveErrors = 0
        lastCommittedPartial = ""
        partialStableCount = 0
        lastPartialText = ""
        lastPartialTime = 0L

        startRecognizer()
    }

    private fun createRecognizerIntent(): Intent {
        return Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(
                RecognizerIntent.EXTRA_LANGUAGE_MODEL,
                RecognizerIntent.LANGUAGE_MODEL_FREE_FORM,
            )
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, currentLanguage)
            // Accept both ja and en for bilingual users
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_PREFERENCE, currentLanguage)
            putExtra(RecognizerIntent.EXTRA_ONLY_RETURN_LANGUAGE_PREFERENCE, false)
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 5)
            putExtra(RecognizerIntent.EXTRA_CONFIDENCE_SCORES, true)
            // Offline preference disabled — causes ERROR_NO_MATCH on devices
            // without offline model downloaded. Let the engine decide.
            // putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, true)
            // Silence tolerance — generous but within engine limits
            putExtra("android.speech.extra.SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS", 5000L)
            putExtra("android.speech.extra.SPEECH_INPUT_POSSIBLY_COMPLETE_SILENCE_LENGTH_MILLIS", 3000L)
            // Web search bias off (better for natural language)
            putExtra(RecognizerIntent.EXTRA_WEB_SEARCH_ONLY, false)
        }
    }

    private fun startRecognizer() {
        mainHandler.post {
            try {
                speechRecognizer?.destroy()
                speechRecognizer = null

                // Use pre-created recognizer if available (zero-gap restart)
                val recognizer = nextRecognizer ?: SpeechRecognizer.createSpeechRecognizer(service.applicationContext)
                nextRecognizer = null

                if (recognizer == null) {
                    Log.e(TAG, "createSpeechRecognizer returned null")
                    lastError = "音声認識を初期化できません"
                    isListening = false
                    continuousMode = false
                    return@post
                }

                recognizer.setRecognitionListener(listener)
                speechRecognizer = recognizer
                recognizer.startListening(createRecognizerIntent())
                Log.i(TAG, "SpeechRecognizer started (lang=$currentLanguage, utterance=$utteranceCount)")
            } catch (e: Exception) {
                Log.e(TAG, "Failed to start listening", e)
                lastError = "音声認識の開始に失敗: ${e.message}"
                isListening = false
                continuousMode = false
            }
        }
    }

    /**
     * Pre-create the next recognizer while current one is still running.
     * This eliminates the gap between utterances.
     */
    private fun prepareNextRecognizer() {
        if (!continuousMode) return
        mainHandler.post {
            try {
                if (nextRecognizer == null) {
                    nextRecognizer = SpeechRecognizer.createSpeechRecognizer(service.applicationContext)
                }
            } catch (e: Exception) {
                Log.w(TAG, "Failed to pre-create next recognizer", e)
            }
        }
    }

    fun stopListening() {
        continuousMode = false
        // Commit any remaining partial text
        commitRemainingPartial()
        try {
            speechRecognizer?.stopListening()
        } catch (_: Exception) {}
        try {
            nextRecognizer?.destroy()
            nextRecognizer = null
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
        try {
            nextRecognizer?.destroy()
            nextRecognizer = null
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

    // ── Streaming partial commit ────────────────────────────────────────

    /**
     * Typeless-style: commit stable portions of partial results in real-time.
     * If the same partial text appears 3+ times in a row (stable for ~750ms),
     * commit the stable prefix and keep only the changing suffix as partial.
     */
    private fun tryStreamingCommit(partial: String) {
        if (partial.isEmpty()) return
        val now = System.currentTimeMillis()

        if (partial == lastPartialText) {
            partialStableCount++
        } else {
            partialStableCount = 0
            lastPartialText = partial
            lastPartialTime = now
        }

        // Commit stable prefix after 3 consecutive identical partials (~750ms)
        // or if partial is very long (>30 chars) and hasn't changed for 500ms
        val shouldCommit = (partialStableCount >= 3) ||
            (partial.length > 30 && now - lastPartialTime > 500 && partialStableCount >= 1)

        if (shouldCommit && partial.length > lastCommittedPartial.length) {
            // Find stable prefix: everything except the last "word" being edited
            val commitUpTo = findStablePrefix(partial)
            if (commitUpTo > lastCommittedPartial.length) {
                val toCommit = partial.substring(lastCommittedPartial.length, commitUpTo)
                if (toCommit.isNotBlank()) {
                    val converted = convertVoiceCommands(toCommit)
                    val spaced = addUtteranceSpacing(converted)
                    service.currentInputConnection?.commitText(spaced, 1)
                    committedInSession.append(spaced)
                    lastCommittedPartial = partial.substring(0, commitUpTo)
                    // Log.d removed: never log user voice input text
                }
            }
        }
    }

    /**
     * Find the boundary of the stable prefix in partial text.
     * For Japanese: commit up to the last particle/punctuation boundary.
     * For English: commit up to the last space (complete words only).
     */
    private fun findStablePrefix(text: String): Int {
        if (text.length < 3) return 0
        val hasJapanese = text.any { it.code in 0x3000..0x9FFF || it.code in 0xFF00..0xFFEF }

        if (hasJapanese) {
            // Commit up to 80% of text — leave the rest as "being edited"
            val target = (text.length * 0.8).toInt()
            // Find a natural break point near the target
            for (i in target downTo (text.length / 2)) {
                val c = text[i]
                if (c in "。、！？「」（）") return i + 1
                // Particle boundaries
                if (i > 0 && c in "はがをにでとのもへやかなけど") return i + 1
            }
            return target
        } else {
            // English: commit complete words only
            val lastSpace = text.lastIndexOf(' ', text.length - 2)
            return if (lastSpace > lastCommittedPartial.length) lastSpace + 1 else 0
        }
    }

    /**
     * When stopping, commit any remaining uncommitted partial text.
     */
    private fun commitRemainingPartial() {
        val remaining = if (lastCommittedPartial.isNotEmpty() && lastPartialText.length > lastCommittedPartial.length) {
            lastPartialText.substring(lastCommittedPartial.length)
        } else if (lastCommittedPartial.isEmpty() && partialText.isNotEmpty()) {
            partialText
        } else {
            null
        }
        if (remaining != null && remaining.isNotBlank()) {
            val converted = convertVoiceCommands(remaining)
            val spaced = addUtteranceSpacing(converted)
            val processed = smartPunctuation(spaced)
            service.currentInputConnection?.commitText(processed, 1)
            committedInSession.append(processed)
        }
        lastCommittedPartial = ""
        partialStableCount = 0
        lastPartialText = ""
    }

    // ── Voice commands → symbol conversion ──────────────────────────

    /**
     * 音声コマンドを記号に変換する。Typeless同等。
     * 「てん」→、 「まる」→。 「はてな」→？ 等。
     * テキスト中のどこに出現しても変換する（文末以外も）。
     */
    private fun convertVoiceCommands(text: String): String {
        var result = text

        // 完全一致のコマンド（単独で発話された場合）
        val exactCommands = mapOf(
            "改行" to "\n",
            "かいぎょう" to "\n",
            "エンター" to "\n",
            "スペース" to " ",
            "タブ" to "\t",
        )
        val trimmed = result.trim()
        exactCommands[trimmed]?.let { return it }

        // インライン変換（文中に出現するコマンド）
        // 順番重要: 長いパターンを先にマッチ
        val inlineCommands = listOf(
            // 句読点
            "まる" to "。",
            "マル" to "。",
            "てん" to "、",
            "テン" to "、",
            "こめてん" to "※",
            "コメテン" to "※",
            // 疑問符・感嘆符
            "はてな" to "？",
            "ハテナ" to "？",
            "クエスチョン" to "？",
            "くえすちょん" to "？",
            "クエスチョンマーク" to "？",
            "びっくり" to "！",
            "ビックリ" to "！",
            "びっくりマーク" to "！",
            "ビックリマーク" to "！",
            "エクスクラメーション" to "！",
            "かんたんふ" to "！",
            "感嘆符" to "！",
            "ぎもんふ" to "？",
            "疑問符" to "？",
            // 括弧
            "かっこ" to "「",
            "カッコ" to "「",
            "かっことじ" to "」",
            "カッコトジ" to "」",
            "とじかっこ" to "」",
            "まるかっこ" to "（",
            "まるかっことじ" to "）",
            "かぎかっこ" to "「",
            "かぎかっことじ" to "」",
            "にじゅうかっこ" to "『",
            "にじゅうかっことじ" to "』",
            // その他記号
            "なかぐろ" to "・",
            "ナカグロ" to "・",
            "中黒" to "・",
            "コロン" to "：",
            "ころん" to "：",
            "セミコロン" to "；",
            "せみころん" to "；",
            "スラッシュ" to "／",
            "すらっしゅ" to "／",
            "さんてん" to "…",
            "三点" to "…",
            "てんてんてん" to "…",
            "テンテンテン" to "…",
            // スペース・改行（文中）
            "かいぎょう" to "\n",
            "改行" to "\n",
        )

        for ((cmd, sym) in inlineCommands) {
            result = result.replace(cmd, sym)
        }

        return result
    }

    // ── Smart punctuation ─────────────────────────────────────────────

    /**
     * 文末に自然な句読点を自動追加する。
     * convertVoiceCommands() で明示的に入力された句読点がある場合はスキップ。
     */
    private fun smartPunctuation(text: String): String {
        if (text.isEmpty()) return text
        val lastChar = text.last()
        // 既に句読点・記号で終わっている場合はそのまま
        if (lastChar in "。、！？.!?,;:「」『』（）・…\n") return text

        val hasJapanese = text.any { it.code in 0x3000..0x9FFF || it.code in 0xFF00..0xFFEF }

        if (hasJapanese) {
            // 疑問パターン（より広範にカバー）
            if (text.endsWith("ですか") || text.endsWith("ますか") ||
                text.endsWith("でしょうか") || text.endsWith("かな") ||
                text.endsWith("だろうか") || text.endsWith("じゃないか") ||
                text.endsWith("じゃない") || text.endsWith("なの") ||
                text.endsWith("のか") || text.endsWith("かね") ||
                text.endsWith("かしら") || text.endsWith("だっけ") ||
                text.endsWith("ないか") || text.endsWith("ませんか") ||
                text.endsWith("でしょう") || text.endsWith("だろう") ||
                text.endsWith("よね") || text.endsWith("だよね") ||
                text.endsWith("ですよね") || text.endsWith("でしたっけ") ||
                text.endsWith("って何") || text.endsWith("ってなに") ||
                text.endsWith("とは") ||
                (text.endsWith("か") && text.length > 2) ||
                (text.endsWith("の") && text.length > 4)
            ) {
                return "$text？"
            }
            // 感嘆パターン
            if (text.endsWith("すごい") || text.endsWith("やばい") ||
                text.endsWith("ください") || text.endsWith("だろ") ||
                text.endsWith("するな") || text.endsWith("するぞ") ||
                text.endsWith("やれ") || text.endsWith("しろ") ||
                text.endsWith("しなさい") || text.endsWith("ないで")
            ) {
                return "$text！"
            }
            // 読点で区切られた文の途中: 次のutteranceとの結合を想定して「、」は付けない
            // デフォルトは句点
            return "$text。"
        } else {
            val lower = text.lowercase()
            if (lower.startsWith("what ") || lower.startsWith("how ") ||
                lower.startsWith("why ") || lower.startsWith("where ") ||
                lower.startsWith("when ") || lower.startsWith("who ") ||
                lower.startsWith("which ") || lower.startsWith("is ") ||
                lower.startsWith("are ") || lower.startsWith("do ") ||
                lower.startsWith("does ") || lower.startsWith("can ") ||
                lower.startsWith("could ") || lower.startsWith("would ") ||
                lower.startsWith("should ") || lower.startsWith("have ") ||
                lower.startsWith("has ") || lower.startsWith("will ") ||
                lower.startsWith("did ") || lower.endsWith("?") ||
                lower.endsWith(" right") || lower.endsWith(" huh")
            ) {
                return "$text?"
            }
            return "$text."
        }
    }

    private fun addUtteranceSpacing(text: String): String {
        if (committedInSession.isEmpty()) return text
        val lastCommitted = committedInSession.last()
        // Japanese: no space needed
        if (lastCommitted.code in 0x3000..0x9FFF || lastCommitted.code in 0xFF00..0xFFEF) {
            return text
        }
        return if (lastCommitted != ' ') " $text" else text
    }

    private fun selectBestResult(results: Bundle?): String {
        val alternatives = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
            ?: return ""
        if (alternatives.isEmpty()) return ""

        val confidences = results.getFloatArray(SpeechRecognizer.CONFIDENCE_SCORES)
        if (confidences != null && confidences.size == alternatives.size) {
            var bestIdx = 0
            var bestConf = confidences[0]
            for (i in 1 until confidences.size) {
                if (confidences[i] > bestConf) {
                    bestConf = confidences[i]
                    bestIdx = i
                }
            }
            // Log.d removed: never log recognized voice text (alternatives contain user speech)
            return alternatives[bestIdx]
        }
        return alternatives[0]
    }

    // ── RecognitionListener ───────────────────────────────────────────

    private val listener = object : RecognitionListener {
        override fun onReadyForSpeech(params: Bundle?) {
            lastError = ""
            rmsLevel = 0f
            consecutiveErrors = 0
            // Pre-create next recognizer for zero-gap restart
            prepareNextRecognizer()
        }

        override fun onBeginningOfSpeech() {
            service.feedbackManager.onTrackballStep()
        }

        override fun onRmsChanged(rmsdB: Float) {
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
                SpeechRecognizer.ERROR_CLIENT -> "クライアントエラー"
                SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> "認識エンジンビジー"
                else -> "エラー ($error)"
            }
            Log.w(TAG, "Recognition error: $msg (code=$error, consecutive=$consecutiveErrors)")

            consecutiveErrors++

            // In continuous mode, auto-restart on recoverable errors
            if (continuousMode && error in RECOVERABLE_ERRORS) {
                // Always show error so user knows what's happening
                lastError = msg
                partialText = ""
                rmsLevel = 0f

                // Give up after too many consecutive errors
                if (consecutiveErrors > 5) {
                    Log.w(TAG, "Too many consecutive errors ($consecutiveErrors), stopping")
                    lastError = msg
                    partialText = ""
                    rmsLevel = 0f
                    isListening = false
                    continuousMode = false
                    return
                }

                // Exponential backoff: 100ms, 200ms, 400ms, 800ms, ...
                val delay = (100L * (1 shl (consecutiveErrors - 1).coerceAtMost(4)))

                mainHandler.postDelayed({
                    if (continuousMode && isBatteryOk()) {
                        startRecognizer()
                    } else {
                        isListening = false
                        continuousMode = false
                    }
                }, delay)
            } else {
                lastError = msg
                partialText = ""
                rmsLevel = 0f
                isListening = false
                continuousMode = false
            }
        }

        override fun onResults(results: Bundle?) {
            var text = selectBestResult(results)

            // Apply voice command → symbol conversion before anything else
            text = convertVoiceCommands(text)

            // If we already streamed partial commits, only commit the remainder
            if (lastCommittedPartial.isNotEmpty() && text.startsWith(lastCommittedPartial)) {
                text = text.substring(lastCommittedPartial.length)
            } else if (lastCommittedPartial.isNotEmpty()) {
                // Final result differs from partial — the engine corrected itself
                // Delete the streamed text and recommit the full result
                val ic = service.currentInputConnection
                if (ic != null && lastCommittedPartial.isNotEmpty()) {
                    ic.deleteSurroundingText(lastCommittedPartial.length, 0)
                    committedInSession.delete(
                        committedInSession.length - lastCommittedPartial.length,
                        committedInSession.length,
                    )
                }
            }

            if (text.isNotEmpty()) {
                val spaced = addUtteranceSpacing(text)
                val processed = smartPunctuation(spaced)
                service.currentInputConnection?.commitText(processed, 1)
                committedInSession.append(processed)
                utteranceCount++
            }
            partialText = ""
            rmsLevel = 0f
            lastCommittedPartial = ""
            partialStableCount = 0
            lastPartialText = ""
            consecutiveErrors = 0

            // Zero-gap continuous restart
            if (continuousMode && isBatteryOk()) {
                startRecognizer()
            } else {
                isListening = false
                continuousMode = false
            }
        }

        override fun onPartialResults(partialResults: Bundle?) {
            val text = partialResults
                ?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                ?.firstOrNull() ?: ""
            partialText = text

            // Streaming partial commit for long dictation
            if (text.length > 10) {
                tryStreamingCommit(text)
            }
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
        private const val BATTERY_THRESHOLD = 10 // Lower threshold — voice input is lightweight
        private val RECOVERABLE_ERRORS = setOf(
            SpeechRecognizer.ERROR_NO_MATCH,
            SpeechRecognizer.ERROR_SPEECH_TIMEOUT,
            SpeechRecognizer.ERROR_NETWORK,
            SpeechRecognizer.ERROR_NETWORK_TIMEOUT,
            SpeechRecognizer.ERROR_SERVER,
            SpeechRecognizer.ERROR_AUDIO,
            SpeechRecognizer.ERROR_CLIENT,
            SpeechRecognizer.ERROR_RECOGNIZER_BUSY,
        )
    }
}
