package space.manus.nacre.config

import android.view.KeyEvent

object PresetProvider {

    enum class PresetType {
        Default, Terminal, Vim, Emacs, Dvorak, Colemak
    }

    fun getLayout(preset: PresetType): KeyboardLayout = when (preset) {
        PresetType.Default -> DefaultLayouts.qwertyBase
        PresetType.Terminal -> terminalLayout
        PresetType.Vim -> vimLayout
        PresetType.Emacs -> emacsLayout
        PresetType.Dvorak -> dvorakLayout
        PresetType.Colemak -> colemakLayout
    }

    // ---- shared modifier row (Row 4) used by all presets ----

    // Row 4: [Esc][Fn][⇧][⎵⎵] | [？][ー]['][あ/A]
    private val modifierRow = listOf(
        KeyDef("Esc", action = KeyAction.Escape, swipeRight = "GL"),
        KeyDef("Fn", action = KeyAction.Fn),
        KeyDef("\u21E7", action = KeyAction.Shift),
        KeyDef(" ", label = "\u23B5", action = KeyAction.Space, widthMultiplier = 2f,
            swipeUp = "Tab", swipeLeft = "\u30FC"),
        KeyDef("\uFF1F", label = "\uFF1F", swipeUp = "?", swipeDown = "\uFF01"),
        KeyDef("\u30FC", label = "\u30FC", swipeUp = "\u301C", swipeDown = "-"),
        KeyDef("'", swipeUp = "\"", swipeDown = ";"),
        KeyDef("\u3042", label = "\u3042", action = KeyAction.ToggleJapanese),
    )

    // ---- helper ----

    private fun key(
        primary: String,
        label: String = primary,
        swipeUp: String? = null,
        swipeDown: String? = null,
        swipeLeft: String? = null,
        swipeRight: String? = null,
    ) = KeyDef(
        primary = primary,
        label = label,
        swipeUp = swipeUp,
        swipeDown = swipeDown,
        swipeLeft = swipeLeft,
        swipeRight = swipeRight,
    )

    // ================================================================
    // Terminal preset
    // Same QWERTY but Ctrl+C/D/Z on base layer, pipe/redirect accessible
    // ================================================================

    private val terminalLayout: KeyboardLayout = KeyboardLayout(
        rows = listOf(
            // Row 1: Q-P (same as default)
            listOf(
                key("q", swipeUp = "1", swipeLeft = "~", swipeRight = "`"),
                key("w", swipeUp = "2"), key("e", swipeUp = "3"),
                key("r", swipeUp = "4"), key("t", swipeUp = "5"),
                key("y", swipeUp = "6"), key("u", swipeUp = "7"),
                key("i", swipeUp = "8"), key("o", swipeUp = "9"),
                key("p", swipeUp = "0", swipeLeft = "[", swipeRight = "]"),
            ),
            // Row 2: A-L + BS with pipe/redirect on swipes
            listOf(
                key("a", swipeUp = "@", swipeLeft = "{", swipeRight = "}"),
                key("s", swipeUp = "#"), key("d", swipeUp = "$"),
                key("f", swipeUp = "%"), key("g", swipeUp = "&"),
                key("h", swipeUp = "|", swipeLeft = "<", swipeRight = ">"),
                key("j", swipeUp = "("), key("k", swipeUp = ")"),
                key("l", swipeUp = "-", swipeRight = "="),
                KeyDef("\u232B", action = KeyAction.Backspace, swipeUp = ":", swipeRight = ";", swipeLeft = "\u232Bw"),
            ),
            // Row 3: Z-M + Enter, with Ctrl shortcuts on swipe-down
            listOf(
                key("z", swipeUp = "!", swipeDown = "C-z"),
                key("x", swipeUp = "\"", swipeDown = "C-x"),
                key("c", swipeUp = "'", swipeDown = "C-c"),
                key("v", swipeUp = "/", swipeRight = "|", swipeDown = "C-d"),
                key("b", swipeUp = "\\"), key("n", swipeUp = "?"),
                key("m", swipeUp = "+", swipeRight = "="),
                key(",", swipeUp = "<", swipeRight = ">"),
                key(".", swipeUp = "\uFF01", swipeDown = "\u3002"),
                KeyDef("\u21B5", action = KeyAction.Enter),
            ),
            // Row 4: Modifiers (shared)
            modifierRow,
        ),
    )

    // ================================================================
    // Vim preset
    // QWERTY with dedicated Esc key, hjkl arrows on Fn layer
    // ================================================================

    private val vimLayout: KeyboardLayout = KeyboardLayout(
        rows = listOf(
            // Row 1: Q-P (10 keys, balanced for V-split)
            // Esc is on Fn long-press per SPEC
            listOf(
                key("q", swipeUp = "1", swipeLeft = "~", swipeRight = "`"),
                key("w", swipeUp = "2"), key("e", swipeUp = "3"),
                key("r", swipeUp = "4"), key("t", swipeUp = "5"),
                key("y", swipeUp = "6"), key("u", swipeUp = "7"),
                key("i", swipeUp = "8"), key("o", swipeUp = "9"),
                key("p", swipeUp = "0", swipeLeft = "[", swipeRight = "]"),
            ),
            // Row 2: A-L + BS
            listOf(
                key("a", swipeUp = "@", swipeLeft = "{", swipeRight = "}"),
                key("s", swipeUp = "#"), key("d", swipeUp = "$"),
                key("f", swipeUp = "%"), key("g", swipeUp = "&"),
                key("h", swipeUp = "*"), key("j", swipeUp = "("),
                key("k", swipeUp = ")"), key("l", swipeUp = "-", swipeRight = "="),
                KeyDef("\u232B", action = KeyAction.Backspace, swipeUp = ":", swipeRight = ";", swipeLeft = "\u232Bw"),
            ),
            // Row 3: Z-. + Enter
            listOf(
                key("z", swipeUp = "!", swipeLeft = "\""),
                key("x", swipeUp = "\"", swipeRight = "'"),
                key("c", swipeUp = "'"),
                key("v", swipeUp = "/", swipeRight = "|"),
                key("b", swipeUp = "\\"), key("n", swipeUp = "?"),
                key("m", swipeUp = "+", swipeRight = "="),
                key(",", swipeUp = "<", swipeRight = ">"),
                key(".", swipeUp = "\uFF01", swipeDown = "\u3002"),
                KeyDef("\u21B5", action = KeyAction.Enter),
            ),
            // Row 4: Modifiers (shared)
            modifierRow,
        ),
    )

    // ================================================================
    // Emacs preset
    // QWERTY with Ctrl as modifier, Ctrl+A/E/K/Y accessible via swipe
    // ================================================================

    private val emacsLayout: KeyboardLayout = KeyboardLayout(
        rows = listOf(
            // Row 1: Q-P
            listOf(
                key("q", swipeUp = "1", swipeLeft = "~", swipeRight = "`"),
                key("w", swipeUp = "2"), key("e", swipeUp = "3"),
                key("r", swipeUp = "4"), key("t", swipeUp = "5"),
                key("y", swipeUp = "6"), key("u", swipeUp = "7"),
                key("i", swipeUp = "8"), key("o", swipeUp = "9"),
                key("p", swipeUp = "0", swipeLeft = "[", swipeRight = "]"),
            ),
            // Row 2: A-L + BS with Ctrl shortcuts on swipes
            listOf(
                KeyDef("a", swipeUp = "@", swipeLeft = "{", swipeRight = "}",
                    longPress = null, action = KeyAction.Text("a"),
                    swipeDown = "C-a"),
                key("s", swipeUp = "#"), key("d", swipeUp = "$"),
                KeyDef("e", swipeUp = "3", action = KeyAction.Text("e"),
                    swipeDown = "C-e"),
                key("f", swipeUp = "%"), key("g", swipeUp = "&"),
                key("h", swipeUp = "*"), key("j", swipeUp = "("),
                KeyDef("k", swipeUp = ")", action = KeyAction.Text("k"),
                    swipeDown = "C-k"),
                KeyDef("\u232B", action = KeyAction.Backspace, swipeUp = ":", swipeRight = ";", swipeLeft = "\u232Bw"),
            ),
            // Row 3: Z-. + Enter
            listOf(
                key("z", swipeUp = "!", swipeLeft = "\""),
                key("x", swipeUp = "\"", swipeRight = "'"),
                key("c", swipeUp = "'"),
                key("v", swipeUp = "/", swipeRight = "|"),
                key("b", swipeUp = "\\"), key("n", swipeUp = "?"),
                key("m", swipeUp = "+", swipeRight = "="),
                key(",", swipeUp = "<", swipeRight = ">"),
                key(".", swipeUp = "\uFF01", swipeDown = "\u3002"),
                KeyDef("\u21B5", action = KeyAction.Enter),
            ),
            // Row 4: Ctrl + modifiers
            listOf(
                KeyDef("Ctrl", action = KeyAction.KeyCode(KeyEvent.KEYCODE_CTRL_LEFT)),
                KeyDef("Fn", action = KeyAction.Fn),
                KeyDef("\u21E7", action = KeyAction.Shift),
                KeyDef(" ", label = "\u23B5", action = KeyAction.Space, widthMultiplier = 2f,
                    swipeUp = "Tab", swipeLeft = "\u30FC"),
                KeyDef("Esc", action = KeyAction.Escape, swipeRight = "GL"),
                KeyDef("\uFF1F", label = "\uFF1F", swipeUp = "?", swipeDown = "\uFF01"),
                KeyDef("\u21B5", action = KeyAction.Enter),
                KeyDef("\u3042", label = "\u3042", action = KeyAction.ToggleJapanese),
            ),
        ),
    )

    // ================================================================
    // Dvorak preset
    // Dvorak letter arrangement: ',.pyfgcrl / aoeuidhtns / ;qjkxbmwvz
    // ================================================================

    private val dvorakLayout: KeyboardLayout = KeyboardLayout(
        rows = listOf(
            // Row 1: ',.pyfgcrl
            listOf(
                key("'", swipeUp = "1", swipeLeft = "~", swipeRight = "`"),
                key(",", swipeUp = "2", swipeDown = "<"),
                key(".", swipeUp = "3", swipeDown = ">"),
                key("p", swipeUp = "4"), key("y", swipeUp = "5"),
                key("f", swipeUp = "6"), key("g", swipeUp = "7"),
                key("c", swipeUp = "8"), key("r", swipeUp = "9"),
                key("l", swipeUp = "0", swipeLeft = "[", swipeRight = "]"),
            ),
            // Row 2: aoeuidhtn + BS
            listOf(
                key("a", swipeUp = "@", swipeLeft = "{", swipeRight = "}"),
                key("o", swipeUp = "#"), key("e", swipeUp = "$"),
                key("u", swipeUp = "%"), key("i", swipeUp = "&"),
                key("d", swipeUp = "*"), key("h", swipeUp = "("),
                key("t", swipeUp = ")"), key("n", swipeUp = "-", swipeRight = "="),
                KeyDef("\u232B", action = KeyAction.Backspace, swipeUp = ":", swipeRight = "s", swipeLeft = "\u232Bw"),
            ),
            // Row 3: ;qjkxbm, + . + Enter (w,v,z on swipes)
            listOf(
                key(";", swipeUp = "!", swipeDown = "z"),
                key("q", swipeUp = "\"", swipeRight = "'"),
                key("j", swipeUp = "'"),
                key("k", swipeUp = "/", swipeRight = "|"),
                key("x", swipeUp = "\\"), key("b", swipeUp = "?"),
                key("m", swipeUp = "+", swipeRight = "=", swipeDown = "w"),
                key(",", swipeUp = "<", swipeDown = "v"),
                key(".", swipeUp = "\uFF01", swipeDown = "\u3002"),
                KeyDef("\u21B5", action = KeyAction.Enter),
            ),
            // Row 4: Modifiers (shared)
            modifierRow,
        ),
    )

    // ================================================================
    // Colemak preset
    // Colemak letter arrangement: qwfpgjluy; / arstdhneio / zxcvbkm,.
    // ================================================================

    private val colemakLayout: KeyboardLayout = KeyboardLayout(
        rows = listOf(
            // Row 1: qwfpgjluy;
            listOf(
                key("q", swipeUp = "1", swipeLeft = "~", swipeRight = "`"),
                key("w", swipeUp = "2"), key("f", swipeUp = "3"),
                key("p", swipeUp = "4"), key("g", swipeUp = "5"),
                key("j", swipeUp = "6"), key("l", swipeUp = "7"),
                key("u", swipeUp = "8"), key("y", swipeUp = "9"),
                key(";", swipeUp = "0", swipeLeft = "[", swipeRight = "]"),
            ),
            // Row 2: arstdhnei + BS
            listOf(
                key("a", swipeUp = "@", swipeLeft = "{", swipeRight = "}"),
                key("r", swipeUp = "#"), key("s", swipeUp = "$"),
                key("t", swipeUp = "%"), key("d", swipeUp = "&"),
                key("h", swipeUp = "*"), key("n", swipeUp = "("),
                key("e", swipeUp = ")"), key("i", swipeUp = "-", swipeRight = "="),
                KeyDef("\u232B", action = KeyAction.Backspace, swipeUp = ":", swipeRight = "o", swipeLeft = "\u232Bw"),
            ),
            // Row 3: zxcvbkm,. + Enter
            listOf(
                key("z", swipeUp = "!", swipeLeft = "\""),
                key("x", swipeUp = "\"", swipeRight = "'"),
                key("c", swipeUp = "'"),
                key("v", swipeUp = "/", swipeRight = "|"),
                key("b", swipeUp = "\\"), key("k", swipeUp = "?"),
                key("m", swipeUp = "+", swipeRight = "="),
                key(",", swipeUp = "<", swipeRight = ">"),
                key(".", swipeUp = "\uFF01", swipeDown = "\u3002"),
                KeyDef("\u21B5", action = KeyAction.Enter),
            ),
            // Row 4: Modifiers (shared)
            modifierRow,
        ),
    )
}
