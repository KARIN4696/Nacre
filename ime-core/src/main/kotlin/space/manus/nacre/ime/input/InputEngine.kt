package space.manus.nacre.ime.input

import android.view.KeyEvent
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.ExtractedTextRequest
import android.view.inputmethod.InputConnection
import space.manus.nacre.config.KeyAction
import space.manus.nacre.config.KeyDef
import space.manus.nacre.ime.NacreInputMethodService

class InputEngine(private val service: NacreInputMethodService) {

    private var editorInfo: EditorInfo? = null
    private val japaneseEngine = JapaneseEngine()
    private var composingText: String = ""

    fun onStartInput(info: EditorInfo?) {
        editorInfo = info
        composingText = ""
        japaneseEngine.reset()
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
        commitText(text)
    }

    fun processAction(action: KeyAction) {
        val ic = service.currentInputConnection ?: return

        when (action) {
            is KeyAction.Text -> {
                if (service.layerManager.isJapanese) {
                    // Japanese mode: always lowercase for romaji
                    processJapaneseInput(action.text.lowercase(), ic)
                } else {
                    val text = if (service.layerManager.isShifted) {
                        action.text.uppercase()
                    } else {
                        action.text
                    }
                    commitText(text)
                    // Auto-unshift after one character (single-shot shift)
                    if (service.layerManager.isShifted) {
                        service.layerManager.toggleShift()
                    }
                }
            }

            is KeyAction.Backspace -> {
                if (composingText.isNotEmpty()) {
                    composingText = composingText.dropLast(1)
                    if (composingText.isEmpty()) {
                        ic.finishComposingText()
                    } else {
                        val kana = japaneseEngine.romajiToHiragana(composingText)
                        ic.setComposingText(kana, 1)
                    }
                } else {
                    ic.deleteSurroundingText(1, 0)
                }
            }

            is KeyAction.Enter -> {
                if (composingText.isNotEmpty()) {
                    finishComposing(ic)
                } else {
                    sendKeyEvent(KeyEvent.KEYCODE_ENTER)
                }
            }

            is KeyAction.Space -> {
                if (composingText.isNotEmpty()) {
                    // TODO: Trigger Mozc conversion for kanji candidates
                    finishComposing(ic)
                } else {
                    commitText(" ")
                }
            }

            is KeyAction.Tab -> sendKeyEvent(KeyEvent.KEYCODE_TAB)
            is KeyAction.Escape -> sendKeyEvent(KeyEvent.KEYCODE_ESCAPE)

            is KeyAction.Shift -> service.layerManager.toggleShift()
            is KeyAction.Fn -> service.layerManager.toggleFn()
            is KeyAction.FnPage2 -> service.layerManager.toggleFn2()

            is KeyAction.SwitchIme -> {
                @Suppress("DEPRECATION")
                service.switchToNextInputMethod(false)
            }

            is KeyAction.ToggleJapanese -> {
                if (composingText.isNotEmpty()) {
                    finishComposing(ic)
                }
                japaneseEngine.reset()
                service.layerManager.toggleJapanese()
            }

            is KeyAction.KeyCode -> {
                if (action.ctrl) {
                    val now = System.currentTimeMillis()
                    ic.sendKeyEvent(
                        KeyEvent(
                            now, now,
                            KeyEvent.ACTION_DOWN, action.code,
                            0, KeyEvent.META_CTRL_ON,
                        ),
                    )
                    ic.sendKeyEvent(
                        KeyEvent(
                            now, now,
                            KeyEvent.ACTION_UP, action.code,
                            0, KeyEvent.META_CTRL_ON,
                        ),
                    )
                } else {
                    sendKeyEvent(action.code)
                }
            }
        }
    }

    private fun processJapaneseInput(text: String, ic: InputConnection) {
        composingText += text
        val kana = japaneseEngine.romajiToHiragana(composingText)
        ic.setComposingText(kana, 1)
    }

    private fun finishComposing(ic: InputConnection) {
        val kana = japaneseEngine.romajiToHiragana(composingText)
        ic.commitText(kana, 1)
        composingText = ""
        japaneseEngine.reset()
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
            // Regular apps: horizontal movement via setSelection
            if (dx != 0) {
                val extracted = ic.getExtractedText(ExtractedTextRequest(), 0) ?: return
                val text = extracted.text?.toString() ?: return
                val pos = extracted.selectionStart
                if (pos < 0) return
                val newPos = (pos + dx).coerceIn(0, text.length)
                ic.setSelection(newPos, newPos)
            }
            // Vertical movement: use DPAD for non-terminal apps too
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
