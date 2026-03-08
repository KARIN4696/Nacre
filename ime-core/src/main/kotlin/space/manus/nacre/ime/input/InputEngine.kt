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
import space.manus.nacre.config.KeyAction
import space.manus.nacre.config.KeyDef
import space.manus.nacre.ime.NacreInputMethodService

class InputEngine(private val service: NacreInputMethodService) {

    private var editorInfo: EditorInfo? = null
    private val japaneseEngine = JapaneseEngine()
    private var composingText: String = ""

    // Conversion state (observable by Compose)
    var candidates = mutableStateListOf<ConversionCandidate>()
        private set
    var selectedCandidateIndex by mutableStateOf(-1)
        private set
    var isConverting by mutableStateOf(false)
        private set

    // Dictionary reference (set from IO thread after loading)
    @Volatile
    var dictionary: DictionaryProvider? = null

    fun onStartInput(info: EditorInfo?) {
        editorInfo = info
        composingText = ""
        japaneseEngine.reset()
        clearCandidates()

        // EditorInfo handling: disable Japanese for password/number fields
        if (info != null) {
            val inputType = info.inputType and InputType.TYPE_MASK_CLASS
            val variation = info.inputType and InputType.TYPE_MASK_VARIATION

            val isPassword = variation == InputType.TYPE_TEXT_VARIATION_PASSWORD ||
                variation == InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD ||
                variation == InputType.TYPE_TEXT_VARIATION_WEB_PASSWORD
            val isNumber = inputType == InputType.TYPE_CLASS_NUMBER ||
                inputType == InputType.TYPE_CLASS_PHONE
            val isEmail = variation == InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS ||
                variation == InputType.TYPE_TEXT_VARIATION_WEB_EMAIL_ADDRESS
            val isUri = variation == InputType.TYPE_TEXT_VARIATION_URI

            val noSuggestions = info.inputType and InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS != 0

            if (isPassword || isNumber || isEmail || isUri || noSuggestions) {
                if (service.layerManager.isJapanese) {
                    service.layerManager.toggleJapanese()
                }
            }
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

        // Commit any active composition before inserting swipe text
        val ic = service.currentInputConnection
        if (ic != null && composingText.isNotEmpty()) {
            if (isConverting) {
                commitSelectedCandidate(ic)
            } else {
                finishComposing(ic)
            }
        }
        commitText(text)
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
                    processJapaneseInput(action.text.lowercase(), ic)
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
                        ic.finishComposingText()
                        clearCandidates()
                    } else {
                        val kana = japaneseEngine.romajiToHiragana(composingText)
                        ic.setComposingText(kana, 1)
                        updatePredictions(kana)
                    }
                } else {
                    ic.deleteSurroundingText(1, 0)
                }
            }

            is KeyAction.Enter -> {
                if (isConverting) {
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

            is KeyAction.Tab -> sendKeyEvent(KeyEvent.KEYCODE_TAB)
            is KeyAction.Escape -> {
                if (isConverting) {
                    cancelConversion(ic)
                } else if (composingText.isNotEmpty()) {
                    composingText = ""
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
            ic.commitText(candidate.surface, 1)
            dictionary?.recordSelection(candidate)
            composingText = ""
            japaneseEngine.reset()
            clearCandidates()
        }
    }

    // --- Japanese conversion ---

    private fun processJapaneseInput(text: String, ic: InputConnection) {
        composingText += text
        val kana = japaneseEngine.romajiToHiragana(composingText)
        ic.setComposingText(kana, 1)
        updatePredictions(kana)
    }

    private fun startConversion(ic: InputConnection) {
        val kana = japaneseEngine.romajiToHiragana(composingText)
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
            val kana = japaneseEngine.romajiToHiragana(composingText)
            ic.commitText(kana, 1)
        }
        composingText = ""
        japaneseEngine.reset()
        clearCandidates()
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
        val dict = dictionary ?: return
        if (kana.isEmpty()) {
            clearCandidates()
            return
        }
        val predictions = dict.predict(kana)
        candidates.clear()
        candidates.addAll(predictions)
        isConverting = false
        selectedCandidateIndex = -1
    }

    private fun finishComposing(ic: InputConnection) {
        val kana = japaneseEngine.romajiToHiragana(composingText)
        ic.commitText(kana, 1)
        composingText = ""
        japaneseEngine.reset()
        clearCandidates()
    }

    private fun clearCandidates() {
        candidates.clear()
        selectedCandidateIndex = -1
        isConverting = false
    }

    /**
     * Remove the last kana unit from romaji composing text.
     * E.g. "kyo" → "" (きょ is one unit), "ka" → "" (か is one unit),
     * "kak" → "ka" (trailing consonant), "kakiko" → "kaki" (remove こ)
     */
    private fun removeLastKanaUnit(romaji: String): String {
        if (romaji.isEmpty()) return ""
        // Try progressively longer suffixes to find one that converts to kana
        for (len in minOf(4, romaji.length) downTo 1) {
            val suffix = romaji.substring(romaji.length - len)
            val kana = japaneseEngine.romajiToHiragana(suffix)
            // If the suffix fully converted (no leftover romaji), it's a kana unit
            if (kana.isNotEmpty() && kana.all { it.code > 0x3000 }) {
                return romaji.substring(0, romaji.length - len)
            }
        }
        // Fallback: just drop last char
        return romaji.dropLast(1)
    }

    private fun commitText(text: String) {
        service.currentInputConnection?.commitText(text, 1)
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
)

interface DictionaryProvider {
    fun convert(kana: String): List<ConversionCandidate>
    fun predict(kana: String): List<ConversionCandidate>
    fun recordSelection(candidate: ConversionCandidate)
}
