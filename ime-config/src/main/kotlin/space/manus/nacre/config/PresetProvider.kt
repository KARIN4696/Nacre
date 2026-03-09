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

    // SPEC Row 4: [Tab][Fn][SP(2x)][⇧] [TR] [BS(1.2x)][EN(1.2x)][GL][.]
    private val modifierRow = listOf(
        KeyDef("Tab", action = KeyAction.Tab),
        KeyDef("Fn", action = KeyAction.Fn),
        KeyDef(" ", label = "\u23B5", action = KeyAction.Space, widthMultiplier = 2f,
            swipeDown = "\u3042", longPress = null),
        KeyDef("\u21E7", action = KeyAction.Shift),
        KeyDef("\u232B", action = KeyAction.Backspace, widthMultiplier = 1.2f,
            swipeLeft = "\u232Bw"),
        KeyDef("\u21B5", action = KeyAction.Enter, widthMultiplier = 1.2f),
        KeyDef("GL", action = KeyAction.SwitchIme),
        KeyDef(".", swipeUp = "_"),
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
            // Row 3: Z-M, with Ctrl shortcuts on swipe-down (all letters kept!)
            listOf(
                key("z", swipeUp = "!", swipeDown = "C-z"),
                key("x", swipeUp = "\"", swipeDown = "C-x"),
                key("c", swipeUp = "'", swipeDown = "C-c"),
                key("v", swipeUp = "/", swipeRight = "|", swipeDown = "C-d"),
                key("b", swipeUp = "\\"), key("n", swipeUp = "?"),
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
            // Row 3: ;qjkxbmwvz (10 keys → 8 visible + v,z on swipes)
            listOf(
                key(";", swipeUp = "!", swipeDown = "z"),
                key("q", swipeUp = "\"", swipeRight = "'"),
                key("j", swipeUp = "'"),
                key("k", swipeUp = "/", swipeRight = "|"),
                key("x", swipeUp = "\\"), key("b", swipeUp = "?"),
                key("m", swipeUp = "+", swipeRight = "="),
                key("w", swipeUp = "<", swipeRight = ">", swipeDown = "v"),
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
