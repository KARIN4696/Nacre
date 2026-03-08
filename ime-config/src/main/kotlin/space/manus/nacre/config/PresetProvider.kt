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

    private val modifierRow = listOf(
        KeyDef("\u21E7", action = KeyAction.Shift),
        KeyDef("Tab", action = KeyAction.Tab),
        KeyDef("Fn", action = KeyAction.Fn),
        KeyDef(" ", label = "\u23B5", action = KeyAction.Space, widthMultiplier = 1.5f),
        KeyDef("\u3042a", action = KeyAction.ToggleJapanese),
        KeyDef("\u232B", action = KeyAction.Backspace, widthMultiplier = 1.1f),
        KeyDef("\u21B5", action = KeyAction.Enter, widthMultiplier = 1.1f),
        KeyDef("GL", action = KeyAction.SwitchIme),
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
            // Row 2: A-; with pipe/redirect on swipes
            listOf(
                key("a", swipeUp = "@", swipeLeft = "{", swipeRight = "}"),
                key("s", swipeUp = "#"), key("d", swipeUp = "$"),
                key("f", swipeUp = "%"), key("g", swipeUp = "&"),
                key("h", swipeUp = "|", swipeLeft = "<", swipeRight = ">"),
                key("j", swipeUp = "("), key("k", swipeUp = ")"),
                key("l", swipeUp = "-", swipeRight = "="),
                key(";", swipeUp = ":", swipeRight = "'"),
            ),
            // Row 3: Ctrl shortcuts on Z/X/C row + pipe/redirect
            listOf(
                KeyDef("C-c", action = KeyAction.KeyCode(KeyEvent.KEYCODE_C, ctrl = true)),
                KeyDef("C-d", action = KeyAction.KeyCode(KeyEvent.KEYCODE_D, ctrl = true)),
                KeyDef("C-z", action = KeyAction.KeyCode(KeyEvent.KEYCODE_Z, ctrl = true)),
                key("|", swipeUp = "\\"),
                key(">", swipeUp = ">>", swipeLeft = "<"),
                key("n", swipeUp = "?"),
                key("m", swipeUp = "+", swipeRight = "="),
                key(",", swipeUp = "<", swipeRight = ">", swipeDown = "."),
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
            // Row 1: Q-P
            listOf(
                KeyDef("Esc", action = KeyAction.Escape),
                key("q", swipeUp = "1"), key("w", swipeUp = "2"),
                key("e", swipeUp = "3"), key("r", swipeUp = "4"),
                key("t", swipeUp = "5"), key("y", swipeUp = "6"),
                key("u", swipeUp = "7"), key("i", swipeUp = "8"),
                key("o", swipeUp = "9"), key("p", swipeUp = "0"),
            ),
            // Row 2: A-;
            listOf(
                key("a", swipeUp = "@", swipeLeft = "{", swipeRight = "}"),
                key("s", swipeUp = "#"), key("d", swipeUp = "$"),
                key("f", swipeUp = "%"), key("g", swipeUp = "&"),
                key("h", swipeUp = "*"), key("j", swipeUp = "("),
                key("k", swipeUp = ")"), key("l", swipeUp = "-", swipeRight = "="),
                key(";", swipeUp = ":", swipeRight = "'"),
            ),
            // Row 3: Z-,
            listOf(
                key("z", swipeUp = "!", swipeLeft = "\""),
                key("x", swipeUp = "\"", swipeRight = "'"),
                key("c", swipeUp = "'"),
                key("v", swipeUp = "/", swipeRight = "|"),
                key("b", swipeUp = "\\"), key("n", swipeUp = "?"),
                key("m", swipeUp = "+", swipeRight = "="),
                key(",", swipeUp = "<", swipeRight = ">", swipeDown = "."),
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
            // Row 2: A-; with Ctrl shortcuts on swipes
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
                key("l", swipeUp = "-", swipeRight = "="),
            ),
            // Row 3: Z-, with Ctrl+Y accessible
            listOf(
                key("z", swipeUp = "!", swipeLeft = "\""),
                key("x", swipeUp = "\"", swipeRight = "'"),
                key("c", swipeUp = "'"),
                key("v", swipeUp = "/", swipeRight = "|"),
                key("b", swipeUp = "\\"), key("n", swipeUp = "?"),
                key("m", swipeUp = "+", swipeRight = "="),
                key(",", swipeUp = "<", swipeRight = ">", swipeDown = "."),
            ),
            // Row 4: Ctrl key replaces Shift position, rest shared
            listOf(
                KeyDef("Ctrl", action = KeyAction.KeyCode(KeyEvent.KEYCODE_CTRL_LEFT)),
                KeyDef("Tab", action = KeyAction.Tab),
                KeyDef("Fn", action = KeyAction.Fn),
                KeyDef(" ", label = "\u23B5", action = KeyAction.Space, widthMultiplier = 1.5f),
                KeyDef("\u3042a", action = KeyAction.ToggleJapanese),
                KeyDef("\u232B", action = KeyAction.Backspace, widthMultiplier = 1.1f),
                KeyDef("\u21B5", action = KeyAction.Enter, widthMultiplier = 1.1f),
                KeyDef("GL", action = KeyAction.SwitchIme),
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
            // Row 2: aoeuidhtns
            listOf(
                key("a", swipeUp = "@", swipeLeft = "{", swipeRight = "}"),
                key("o", swipeUp = "#"), key("e", swipeUp = "$"),
                key("u", swipeUp = "%"), key("i", swipeUp = "&"),
                key("d", swipeUp = "*"), key("h", swipeUp = "("),
                key("t", swipeUp = ")"), key("n", swipeUp = "-", swipeRight = "="),
                key("s", swipeUp = ":", swipeRight = ";"),
            ),
            // Row 3: ;qjkxbmwvz
            listOf(
                key(";", swipeUp = "!", swipeLeft = "\""),
                key("q", swipeUp = "\"", swipeRight = "'"),
                key("j", swipeUp = "'"),
                key("k", swipeUp = "/", swipeRight = "|"),
                key("x", swipeUp = "\\"), key("b", swipeUp = "?"),
                key("m", swipeUp = "+", swipeRight = "="),
                key("w", swipeUp = "<", swipeRight = ">"),
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
            // Row 2: arstdhneio
            listOf(
                key("a", swipeUp = "@", swipeLeft = "{", swipeRight = "}"),
                key("r", swipeUp = "#"), key("s", swipeUp = "$"),
                key("t", swipeUp = "%"), key("d", swipeUp = "&"),
                key("h", swipeUp = "*"), key("n", swipeUp = "("),
                key("e", swipeUp = ")"), key("i", swipeUp = "-", swipeRight = "="),
                key("o", swipeUp = ":", swipeRight = "'"),
            ),
            // Row 3: zxcvbkm,.
            listOf(
                key("z", swipeUp = "!", swipeLeft = "\""),
                key("x", swipeUp = "\"", swipeRight = "'"),
                key("c", swipeUp = "'"),
                key("v", swipeUp = "/", swipeRight = "|"),
                key("b", swipeUp = "\\"), key("k", swipeUp = "?"),
                key("m", swipeUp = "+", swipeRight = "="),
                key(",", swipeUp = "<", swipeRight = ">", swipeDown = "."),
            ),
            // Row 4: Modifiers (shared)
            modifierRow,
        ),
    )
}
