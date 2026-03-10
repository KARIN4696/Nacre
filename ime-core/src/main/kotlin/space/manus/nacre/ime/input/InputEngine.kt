package space.manus.nacre.ime.input

import android.view.KeyEvent
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.ExtractedTextRequest
import android.view.inputmethod.InputConnection
import android.text.InputType
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import android.util.Log
import space.manus.nacre.config.KeyAction
import space.manus.nacre.config.KeyDef
import space.manus.nacre.ime.NacreInputMethodService

private val CTRL_PATTERN = Regex("^C-([a-zA-Z])$")

class InputEngine(private val service: NacreInputMethodService) {

    private val scope = MainScope()
    private var editorInfo: EditorInfo? = null
    private val japaneseEngine = JapaneseEngine()
    private var composingText: String = ""
    private var predictionJob: Job? = null

    // LLM-based candidate reranking (optional, async)
    val llmReranker = LlmReranker(service)

    // Observable composing kana for CandidateBar display
    var composingKana by mutableStateOf("")
        private set

    // Conversion state (observable by Compose)
    var candidates = mutableStateListOf<ConversionCandidate>()
        private set
    var selectedCandidateIndex by mutableStateOf(-1)
        private set
    var isConverting by mutableStateOf(false)
        private set

    // Segment boundary adjustment (文節区切り修正)
    // During conversion, segmentBoundary = number of kana chars in current first segment
    private var segmentBoundary: Int = 0
    private var fullKana: String = "" // Full kana being converted

    // Password field state (observable by Compose for CandidateBar hiding)
    var isPasswordField by mutableStateOf(false)
        private set

    // Dictionary reference (set from IO thread after loading)
    @Volatile
    var dictionary: DictionaryProvider? = null

    // Debug: observable dictionary load state for UI
    var dictionaryLoaded by mutableStateOf(false)

    // Debug: last prediction info
    var debugInfo by mutableStateOf("")

    /**
     * Called when dictionary finishes loading. If there's active composing text,
     * regenerate predictions so candidates appear retroactively.
     */
    fun refreshPredictionsIfNeeded() {
        if (composingText.isNotEmpty() && !isConverting) {
            val kana = japaneseEngine.romajiToHiragana(composingText)
            Log.d("InputEngine", "refreshPredictionsIfNeeded: kana=$kana")
            updatePredictions(kana)
        }
    }

    fun onStartInput(info: EditorInfo?) {
        editorInfo = info
        composingText = ""
        composingKana = ""
        japaneseEngine.reset()
        clearCandidates()

        // EditorInfo handling: disable Japanese for password/number fields
        if (info != null) {
            val rawInputType = info.inputType
            val inputType = rawInputType and InputType.TYPE_MASK_CLASS
            val variation = rawInputType and InputType.TYPE_MASK_VARIATION

            // Only check password variation when the class is actually TYPE_CLASS_TEXT
            // Termux uses TYPE_CLASS_TEXT with TYPE_TEXT_VARIATION_NORMAL (0x0) or TYPE_NULL
            val isTextClass = inputType == InputType.TYPE_CLASS_TEXT
            val isPassword = isTextClass && (
                variation == InputType.TYPE_TEXT_VARIATION_PASSWORD ||
                variation == InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD ||
                variation == InputType.TYPE_TEXT_VARIATION_WEB_PASSWORD
            )
            val isNumber = inputType == InputType.TYPE_CLASS_NUMBER ||
                inputType == InputType.TYPE_CLASS_PHONE
            val isEmail = isTextClass && (
                variation == InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS ||
                variation == InputType.TYPE_TEXT_VARIATION_WEB_EMAIL_ADDRESS
            )
            val isUri = isTextClass && variation == InputType.TYPE_TEXT_VARIATION_URI

            val noSuggestions = rawInputType and InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS != 0

            // debugInfo removed — no longer shown in candidate bar

            // SPEC: Password field — disable prediction, history, AI, hide candidate bar
            isPasswordField = isPassword

            if (isPassword || isNumber || isEmail || isUri) {
                if (service.layerManager.isJapanese) {
                    service.layerManager.toggleJapanese()
                }
            }
            // Note: noSuggestions flag alone does NOT disable Japanese
            // (e.g. Termux uses TYPE_TEXT_FLAG_NO_SUGGESTIONS but user still wants kana)
        } else {
            isPasswordField = false
        }
    }

    fun processKey(keyDef: KeyDef) {
        processAction(keyDef.action)
    }

    fun processSwipe(keyDef: KeyDef, direction: SwipeDirection) {
        val text = when (direction) {
            SwipeDirection.Up -> keyDef.swipeUp
            SwipeDirection.Down -> keyDef.swipeDown
            SwipeDirection.Left -> keyDef.swipeLeft
            SwipeDirection.Right -> keyDef.swipeRight
        } ?: return

        // Parse Emacs-style "C-x" notation as Ctrl+key
        val ctrlMatch = CTRL_PATTERN.matchEntire(text)
        if (ctrlMatch != null) {
            val letter = ctrlMatch.groupValues[1].uppercase()
            val keyCode = KeyEvent.keyCodeFromString("KEYCODE_$letter")
            if (keyCode != KeyEvent.KEYCODE_UNKNOWN) {
                processAction(KeyAction.KeyCode(keyCode, ctrl = true))
                return
            }
        }

        // Space swipe-down = Toggle Japanese (あ indicator)
        if (text == "\u3042" && keyDef.action is KeyAction.Space) {
            processAction(KeyAction.ToggleJapanese)
            return
        }

        // "ToggleJa" swipe = Toggle Japanese input
        if (text == "ToggleJa") {
            processAction(KeyAction.ToggleJapanese)
            return
        }

        // "GL" swipe = Switch IME
        if (text == "GL") {
            processAction(KeyAction.SwitchIme)
            return
        }

        // "Tab" swipe = send Tab key
        if (text == "Tab") {
            processAction(KeyAction.Tab)
            return
        }

        // BS swipe-left = word delete
        if (text == "\u232Bw" && keyDef.action is KeyAction.Backspace) {
            val ic = service.currentInputConnection ?: return
            // Delete word to the left: find last word boundary
            val extracted = ic.getExtractedText(ExtractedTextRequest(), 0)
            val fullText = extracted?.text?.toString() ?: ""
            val pos = extracted?.selectionStart ?: 0
            if (pos > 0) {
                val before = fullText.substring(0, pos)
                val trimmed = before.trimEnd()
                val lastSpace = trimmed.lastIndexOf(' ')
                val deleteCount = pos - if (lastSpace >= 0) lastSpace + 1 else 0
                if (deleteCount > 0) {
                    ic.deleteSurroundingText(deleteCount, 0)
                }
            }
            return
        }

        // Period key swipe-right = Shift toggle (⇧)
        if (text == "\u21E7") {
            processAction(KeyAction.Shift)
            return
        }

        // In Japanese mode, route "-" and "ー" through Japanese input
        // so they join the composing text as long vowel mark
        if (service.layerManager.isJapanese && (text == "-" || text == "ー")) {
            val ic = service.currentInputConnection ?: return
            if (text == "ー") {
                // Direct kana: append to composing text as-is
                composingText += "-"  // store as romaji "-" so engine converts to ー
                val kana = japaneseEngine.romajiToHiragana(composingText)
                composingKana = kana
                ic.setComposingText(kana, 1)
                updatePredictions(kana)
            } else {
                processJapaneseInput(text, ic)
            }
            return
        }

        // Commit any active composition before inserting swipe text
        val ic = service.currentInputConnection
        if (ic != null && composingText.isNotEmpty()) {
            if (isConverting) {
                commitSelectedCandidate(ic)
            } else {
                finishComposing(ic)
            }
        }

        // Japanese mode: auto-convert punctuation to fullwidth
        if (service.layerManager.isJapanese) {
            val jaSwipe = when (text) {
                "?" -> { commitTextWithSymbol(ic ?: service.currentInputConnection ?: return, "？", listOf("？", "?", "⁉", "❓")); return }
                "!" -> { commitTextWithSymbol(ic ?: service.currentInputConnection ?: return, "！", listOf("！", "!", "‼", "❗")); return }
                else -> text
            }
            commitText(jaSwipe)
        } else {
            commitText(text)
        }
    }

    fun processAction(action: KeyAction) {
        val ic = service.currentInputConnection ?: return

        when (action) {
            is KeyAction.Text -> {
                if (isConverting) {
                    // If we're showing candidates, commit selected and start fresh
                    commitSelectedCandidate(ic)
                }
                if (service.layerManager.isJapanese) {
                    // Japanese punctuation/symbols: commit composing first, then insert directly
                    val jaText = when (action.text) {
                        "," -> { if (composingText.isNotEmpty()) finishComposing(ic); ic.commitText("、", 1); showSymbolCandidates("、", listOf("、", "，", "‥")); return }
                        "." -> { if (composingText.isNotEmpty()) finishComposing(ic); ic.commitText("。", 1); showSymbolCandidates("。", listOf("。", "…", "‥", "・", ".")); return }
                        "?", "？" -> { if (composingText.isNotEmpty()) finishComposing(ic); ic.commitText("？", 1); showSymbolCandidates("？", listOf("？", "?", "⁉", "❓")); return }
                        "!", "！" -> { if (composingText.isNotEmpty()) finishComposing(ic); ic.commitText("！", 1); showSymbolCandidates("！", listOf("！", "!", "‼", "❗")); return }
                        "(" -> { if (composingText.isNotEmpty()) finishComposing(ic); ic.commitText("（", 1); showSymbolCandidates("（", listOf("（", "「", "『", "【", "〈", "《", "[", "(")); return }
                        ")" -> { if (composingText.isNotEmpty()) finishComposing(ic); ic.commitText("）", 1); showSymbolCandidates("）", listOf("）", "」", "』", "】", "〉", "》", "]", ")")); return }
                        "[" -> { if (composingText.isNotEmpty()) finishComposing(ic); ic.commitText("「", 1); showSymbolCandidates("「", listOf("「", "『", "【", "〈", "《", "（", "[")); return }
                        "]" -> { if (composingText.isNotEmpty()) finishComposing(ic); ic.commitText("」", 1); showSymbolCandidates("」", listOf("」", "』", "】", "〉", "》", "）", "]")); return }
                        "ー" -> {
                            // Long vowel mark: append to composing text as "-"
                            composingText += "-"
                            val kana = japaneseEngine.romajiToHiragana(composingText)
                            composingKana = kana
                            ic.setComposingText(kana, 1)
                            updatePredictions(kana)
                            return
                        }
                        else -> action.text.lowercase()
                    }
                    processJapaneseInput(jaText, ic)
                } else {
                    val text = if (service.layerManager.isShifted) {
                        action.text.uppercase()
                    } else {
                        action.text
                    }
                    commitText(text)
                    if (service.layerManager.isShifted) {
                        service.layerManager.toggleShift()
                    }
                }
            }

            is KeyAction.Backspace -> {
                if (isConverting) {
                    // Cancel conversion, go back to kana
                    cancelConversion(ic)
                } else if (composingText.isNotEmpty()) {
                    // Remove one kana unit worth of romaji
                    composingText = removeLastKanaUnit(composingText)
                    if (composingText.isEmpty()) {
                        composingKana = ""
                        ic.finishComposingText()
                        clearCandidates()
                    } else {
                        val kana = japaneseEngine.romajiToHiragana(composingText)
                        composingKana = kana
                        ic.setComposingText(kana, 1)
                        updatePredictions(kana)
                    }
                } else {
                    ic.deleteSurroundingText(1, 0)
                }
            }

            is KeyAction.Enter -> {
                if (symbolReplace != null) {
                    // Symbol candidates showing (e.g. after 、。) — just dismiss
                    clearCandidates()
                } else if (isConverting) {
                    commitSelectedCandidate(ic)
                } else if (composingText.isNotEmpty()) {
                    finishComposing(ic)
                } else {
                    // Use performEditorAction for Search/Send/Go fields
                    val imeAction = editorInfo?.imeOptions?.and(EditorInfo.IME_MASK_ACTION) ?: 0
                    if (imeAction == EditorInfo.IME_ACTION_SEARCH ||
                        imeAction == EditorInfo.IME_ACTION_SEND ||
                        imeAction == EditorInfo.IME_ACTION_GO ||
                        imeAction == EditorInfo.IME_ACTION_DONE
                    ) {
                        ic.performEditorAction(imeAction)
                    } else {
                        sendKeyEvent(KeyEvent.KEYCODE_ENTER)
                    }
                }
            }

            is KeyAction.Space -> {
                // Fn + Space → command palette
                if (service.layerManager.currentLayer != space.manus.nacre.ime.input.Layer.Base) {
                    service.layerManager.requestCommandPalette()
                    service.layerManager.resetToBase()
                    return
                }
                if (composingText.isNotEmpty()) {
                    if (isConverting) {
                        // Cycle to next candidate
                        nextCandidate(ic)
                    } else {
                        // Start conversion
                        startConversion(ic)
                    }
                } else {
                    commitText(" ")
                }
            }

            is KeyAction.Tab -> {
                // If a snippet session is active, advance to next tab stop
                if (service.snippetEngine.hasActiveSession) {
                    service.snippetEngine.nextTabStop(ic)
                } else {
                    sendKeyEvent(KeyEvent.KEYCODE_TAB)
                }
            }
            is KeyAction.Escape -> {
                if (isConverting) {
                    cancelConversion(ic)
                } else if (composingText.isNotEmpty()) {
                    composingText = ""
        composingKana = ""
                    ic.finishComposingText()
                    clearCandidates()
                } else {
                    sendKeyEvent(KeyEvent.KEYCODE_ESCAPE)
                }
            }

            is KeyAction.Shift -> service.layerManager.toggleShift()
            is KeyAction.Fn -> service.layerManager.toggleFn()
            is KeyAction.FnPage2 -> service.layerManager.toggleFn2()

            is KeyAction.SwitchIme -> {
                @Suppress("DEPRECATION")
                service.switchToNextInputMethod(false)
            }

            is KeyAction.ToggleJapanese -> {
                if (isConverting) commitSelectedCandidate(ic)
                if (composingText.isNotEmpty()) finishComposing(ic)
                japaneseEngine.reset()
                service.layerManager.toggleJapanese()
            }

            is KeyAction.Emoji -> {
                if (isConverting) commitSelectedCandidate(ic)
                if (composingText.isNotEmpty()) finishComposing(ic)
                service.layerManager.isEmojiRequested = true
            }

            is KeyAction.KeyCode -> {
                if (action.ctrl) {
                    val now = System.currentTimeMillis()
                    ic.sendKeyEvent(
                        KeyEvent(now, now, KeyEvent.ACTION_DOWN, action.code, 0, KeyEvent.META_CTRL_ON),
                    )
                    ic.sendKeyEvent(
                        KeyEvent(now, now, KeyEvent.ACTION_UP, action.code, 0, KeyEvent.META_CTRL_ON),
                    )
                } else {
                    sendKeyEvent(action.code)
                }
            }
        }
    }

    // --- Candidate selection from UI ---

    fun selectCandidate(index: Int) {
        val ic = service.currentInputConnection ?: return
        if (index in candidates.indices) {
            selectedCandidateIndex = index
            ic.setComposingText(candidates[index].surface, 1)
        }
    }

    fun commitCandidate(index: Int) {
        val ic = service.currentInputConnection ?: return
        if (index in candidates.indices) {
            val candidate = candidates[index]

            // Symbol replacement: delete the just-committed symbol and insert the selected one
            val replacing = symbolReplace
            if (replacing != null) {
                ic.deleteSurroundingText(replacing.length, 0)
                ic.commitText(candidate.surface, 1)
                clearCandidates()
                return
            }

            // Clear composing text first to avoid double input
            // (commitText auto-finishes composing, causing composing + candidate duplication)
            if (composingText.isNotEmpty()) {
                ic.setComposingText("", 0)
            }
            ic.finishComposingText()
            ic.commitText(candidate.surface, 1)
            dictionary?.recordSelection(candidate)
            composingText = ""
            composingKana = ""
            japaneseEngine.reset()
            clearCandidates()
            // Show next-word predictions after committing
            showNextWordPredictions()
        }
    }

    // --- Japanese conversion ---

    private fun processJapaneseInput(text: String, ic: InputConnection) {
        composingText += text
        val kana = japaneseEngine.romajiToHiragana(composingText)
        composingKana = kana
        ic.setComposingText(kana, 1)
        updatePredictions(kana)
    }

    private fun startConversion(ic: InputConnection) {
        val kana = japaneseEngine.romajiToHiragana(composingText, finalize = true)
        fullKana = kana
        segmentBoundary = kana.length // Default: entire kana is one segment
        val dict = dictionary
        if (dict != null) {
            val results = dict.convert(kana)
            if (results.isNotEmpty()) {
                candidates.clear()
                candidates.addAll(results)
                selectedCandidateIndex = 0
                isConverting = true
                ic.setComposingText(results[0].surface, 1)
                return
            }
        }
        // No dictionary or no results: commit as kana
        finishComposing(ic)
    }

    /**
     * Shrink or extend the first segment boundary during conversion.
     * Left = shrink (fewer kana in first segment), Right = extend.
     * Re-converts with new boundary and shows candidates for that segment.
     */
    fun adjustSegmentBoundary(direction: SwipeDirection) {
        if (!isConverting || fullKana.isEmpty()) return
        val ic = service.currentInputConnection ?: return
        val dict = dictionary ?: return

        when (direction) {
            SwipeDirection.Left -> {
                if (segmentBoundary > 1) segmentBoundary--
            }
            SwipeDirection.Right -> {
                if (segmentBoundary < fullKana.length) segmentBoundary++
            }
            else -> return
        }

        // Convert only the first segment
        val firstSegKana = fullKana.substring(0, segmentBoundary)
        val restKana = fullKana.substring(segmentBoundary)

        val firstResults = dict.convert(firstSegKana)
        val firstSurface = firstResults.firstOrNull()?.surface ?: firstSegKana

        // Build display: converted first segment + remaining kana
        val displayText = firstSurface + restKana

        // Update candidates for the first segment only
        candidates.clear()
        for (r in firstResults) {
            // Append remaining kana to each candidate surface for full display
            candidates.add(ConversionCandidate(
                surface = r.surface + restKana,
                reading = fullKana,
                cost = r.cost,
            ))
        }
        // Also add the first segment as hiragana + rest
        val allHiragana = fullKana
        if (candidates.none { it.surface == allHiragana }) {
            candidates.add(ConversionCandidate(surface = allHiragana, reading = fullKana, cost = 9999))
        }

        selectedCandidateIndex = 0
        ic.setComposingText(displayText, 1)
        service.feedbackManager.onSwipe()
    }

    private fun nextCandidate(ic: InputConnection) {
        if (candidates.isEmpty()) return
        selectedCandidateIndex = (selectedCandidateIndex + 1) % candidates.size
        ic.setComposingText(candidates[selectedCandidateIndex].surface, 1)
    }

    private fun commitSelectedCandidate(ic: InputConnection) {
        if (candidates.isNotEmpty() && selectedCandidateIndex in candidates.indices) {
            val candidate = candidates[selectedCandidateIndex]
            ic.commitText(candidate.surface, 1)
            dictionary?.recordSelection(candidate)
        } else {
            val kana = japaneseEngine.romajiToHiragana(composingText, finalize = true)
            ic.commitText(kana, 1)
        }
        composingText = ""
        composingKana = ""
        japaneseEngine.reset()
        clearCandidates()
        // Show next-word predictions based on context
        showNextWordPredictions()
    }

    private fun cancelConversion(ic: InputConnection) {
        val kana = japaneseEngine.romajiToHiragana(composingText)
        ic.setComposingText(kana, 1)
        isConverting = false
        selectedCandidateIndex = -1
        // Keep prediction candidates but not conversion candidates
        updatePredictions(kana)
    }

    private fun updatePredictions(kana: String) {
        // SPEC: disable prediction in password fields
        if (isPasswordField) return
        val dict = dictionary ?: return
        if (kana.isEmpty()) {
            clearCandidates()
            return
        }
        val romaji = composingText // Pass raw romaji for English candidate matching
        // Cancel previous prediction job — debounce for fast typing
        predictionJob?.cancel()
        predictionJob = scope.launch {
            // Debounce: wait 50ms before starting prediction to batch rapid keystrokes
            kotlinx.coroutines.delay(50L)
            // Run dictionary lookup off the main thread
            val predictions = withContext(Dispatchers.Default) {
                dict.predict(kana, romaji)
            }
            // Update in one snapshot to avoid Compose seeing empty list
            androidx.compose.runtime.snapshots.Snapshot.withMutableSnapshot {
                candidates.clear()
                candidates.addAll(predictions)
                isConverting = false
                selectedCandidateIndex = -1
            }

            // Async LLM reranking (non-blocking — updates candidates in-place when done)
            if (predictions.size > 1) {
                val precedingText = service.currentInputConnection
                    ?.getTextBeforeCursor(50, 0)?.toString() ?: ""
                llmReranker.rerankAsync(kana, predictions, precedingText) { reranked ->
                    // Only apply if candidates haven't changed since we started
                    if (candidates.size == reranked.size &&
                        candidates.firstOrNull()?.reading == reranked.firstOrNull()?.reading
                    ) {
                        androidx.compose.runtime.snapshots.Snapshot.withMutableSnapshot {
                            candidates.clear()
                            candidates.addAll(reranked)
                            selectedCandidateIndex = -1
                        }
                    }
                }
            }
        }
    }

    /**
     * Show next-word predictions based on recently committed context.
     * Uses bigram/trigram history to suggest likely follow-up words.
     */
    private fun showNextWordPredictions() {
        if (isPasswordField) return
        if (!service.layerManager.isJapanese) return
        val nacrDict = dictionary as? NacreDictionary ?: return
        predictionJob?.cancel()
        predictionJob = scope.launch {
            val predictions = withContext(Dispatchers.Default) {
                nacrDict.predictNextWord(limit = 8)
            }
            if (predictions.isNotEmpty() && composingText.isEmpty()) {
                androidx.compose.runtime.snapshots.Snapshot.withMutableSnapshot {
                    candidates.clear()
                    candidates.addAll(predictions)
                    isConverting = false
                    selectedCandidateIndex = -1
                }
            }
        }
    }

    private fun finishComposing(ic: InputConnection) {
        val kana = japaneseEngine.romajiToHiragana(composingText, finalize = true)
        ic.commitText(kana, 1)
        // Update bigram context
        (dictionary as? NacreDictionary)?.updateContext(kana)
        composingText = ""
        composingKana = ""
        japaneseEngine.reset()
        clearCandidates()
    }

    private fun clearCandidates() {
        candidates.clear()
        selectedCandidateIndex = -1
        isConverting = false
        symbolReplace = null
    }

    // Symbol candidate state: when non-null, tapping a candidate replaces the last committed symbol
    private var symbolReplace: String? = null

    /** Commit a fullwidth symbol and show alternatives */
    private fun commitTextWithSymbol(ic: InputConnection, symbol: String, alternatives: List<String>) {
        ic.commitText(symbol, 1)
        showSymbolCandidates(symbol, alternatives)
    }

    /**
     * Show symbol alternatives in the candidate bar.
     * Tapping a candidate replaces the just-committed symbol.
     */
    private fun showSymbolCandidates(committed: String, alternatives: List<String>) {
        symbolReplace = committed
        androidx.compose.runtime.snapshots.Snapshot.withMutableSnapshot {
            candidates.clear()
            candidates.addAll(alternatives.map { sym ->
                ConversionCandidate(surface = sym, reading = committed, cost = 0)
            })
            selectedCandidateIndex = 0 // First one is the already-committed symbol
            isConverting = false
        }
    }

    /**
     * Remove the last kana unit from romaji composing text.
     * Compares kana output to find how many romaji chars produce the last kana.
     * E.g. "ka" → "" (か), "kyo" → "" (きょ), "kakiko" → "kaki" (remove こ)
     * Trailing unconverted consonant: "kak" → "ka" (remove trailing k)
     */
    private fun removeLastKanaUnit(romaji: String): String {
        if (romaji.isEmpty()) return ""

        val currentKana = japaneseEngine.romajiToHiragana(romaji)

        // Try removing 1, 2, 3, 4 chars from the end — find the shortest removal
        // that actually changes the kana output (i.e., removes one kana unit)
        for (drop in 1..minOf(4, romaji.length)) {
            val shortened = romaji.dropLast(drop)
            val shortenedKana = japaneseEngine.romajiToHiragana(shortened)
            // Check if we removed exactly one kana (or trailing unconverted chars)
            if (shortenedKana.length < currentKana.length) {
                return shortened
            }
        }

        // Fallback: drop 1 char
        return romaji.dropLast(1)
    }

    private fun commitText(text: String) {
        val ic = service.currentInputConnection ?: return
        ic.commitText(text, 1)

        // Single IPC call for both auto-convert and macro checks
        val extracted = ic.getExtractedText(ExtractedTextRequest(), 0)
        val fullText = extracted?.text?.toString() ?: ""

        // Check auto-convert rules (e.g. "->" → "→")
        if (service.autoConvertEngine.isEnabled && fullText.isNotEmpty()) {
            service.autoConvertEngine.checkAndConvert(fullText, ic)
        }

        // Check macro triggers (e.g. ";gs" → "git status" + Enter)
        val macro = service.macroEngine.checkTrigger(fullText)
        if (macro != null && macro.trigger != null) {
            ic.deleteSurroundingText(macro.trigger.length, 0)
            // Re-fetch IC inside coroutine to avoid stale reference
            scope.launch {
                val freshIc = service.currentInputConnection ?: return@launch
                service.macroEngine.executeMacro(macro, freshIc)
            }
        }
    }

    private fun sendKeyEvent(keyCode: Int) {
        val ic = service.currentInputConnection ?: return
        ic.sendKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, keyCode))
        ic.sendKeyEvent(KeyEvent(KeyEvent.ACTION_UP, keyCode))
    }

    fun moveCursor(dx: Int, dy: Int) {
        val ic = service.currentInputConnection ?: return
        val ei = editorInfo

        if (isTerminalApp(ei)) {
            repeat(kotlin.math.abs(dx)) {
                sendKeyEvent(if (dx > 0) KeyEvent.KEYCODE_DPAD_RIGHT else KeyEvent.KEYCODE_DPAD_LEFT)
            }
            repeat(kotlin.math.abs(dy)) {
                sendKeyEvent(if (dy > 0) KeyEvent.KEYCODE_DPAD_DOWN else KeyEvent.KEYCODE_DPAD_UP)
            }
        } else {
            if (dx != 0) {
                val extracted = ic.getExtractedText(ExtractedTextRequest(), 0) ?: return
                val text = extracted.text?.toString() ?: return
                val pos = extracted.selectionStart
                if (pos < 0) return
                val newPos = (pos + dx).coerceIn(0, text.length)
                ic.setSelection(newPos, newPos)
            }
            if (dy != 0) {
                repeat(kotlin.math.abs(dy)) {
                    sendKeyEvent(if (dy > 0) KeyEvent.KEYCODE_DPAD_DOWN else KeyEvent.KEYCODE_DPAD_UP)
                }
            }
        }
    }

    fun destroy() {
        llmReranker.destroy()
        scope.cancel()
    }

    private fun isTerminalApp(info: EditorInfo?): Boolean {
        val pkg = info?.packageName ?: return false
        return pkg.contains("termux") || pkg.contains("terminal") || pkg.contains("connectbot")
    }
}

enum class SwipeDirection { Up, Down, Left, Right }

data class ConversionCandidate(
    val surface: String,
    val reading: String,
    val cost: Int = 0,
    val segments: List<String> = emptyList(),
)

interface DictionaryProvider {
    fun convert(kana: String): List<ConversionCandidate>
    fun predict(kana: String, romaji: String = ""): List<ConversionCandidate>
    fun recordSelection(candidate: ConversionCandidate)
}
