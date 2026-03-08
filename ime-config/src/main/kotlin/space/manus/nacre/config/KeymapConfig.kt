package space.manus.nacre.config

data class KeyDef(
    val primary: String,
    val label: String = primary,
    val longPress: String? = null,
    val swipeUp: String? = null,
    val swipeDown: String? = null,
    val swipeLeft: String? = null,
    val swipeRight: String? = null,
    val widthMultiplier: Float = 1f,
    val action: KeyAction = KeyAction.Text(primary),
)

sealed class KeyAction {
    data class Text(val text: String) : KeyAction()
    data class KeyCode(val code: Int, val ctrl: Boolean = false) : KeyAction()
    data object Backspace : KeyAction()
    data object Enter : KeyAction()
    data object Space : KeyAction()
    data object Shift : KeyAction()
    data object Fn : KeyAction()
    data object FnPage2 : KeyAction()
    data object Escape : KeyAction()
    data object Tab : KeyAction()
    data object SwitchIme : KeyAction()
    data object ToggleJapanese : KeyAction()
}

data class KeyboardLayout(
    val rows: List<List<KeyDef>>,
)

object DefaultLayouts {

    val qwertyBase: KeyboardLayout = KeyboardLayout(
        rows = listOf(
            // Row 1: Q-P
            listOf(
                key("q", swipeUp = "1"), key("w", swipeUp = "2"), key("e", swipeUp = "3"),
                key("r", swipeUp = "4"), key("t", swipeUp = "5"),
                // Trackball placeholder — handled separately
                key("y", swipeUp = "6"), key("u", swipeUp = "7"), key("i", swipeUp = "8"),
                key("o", swipeUp = "9"), key("p", swipeUp = "0"),
            ),
            // Row 2: A-;
            listOf(
                key("a", swipeUp = "@"), key("s", swipeUp = "#"), key("d", swipeUp = "$"),
                key("f", swipeUp = "%"), key("g", swipeUp = "&"),
                key("h", swipeUp = "*"), key("j", swipeUp = "("), key("k", swipeUp = ")"),
                key("l", swipeUp = "-"), key(";", label = ";", swipeUp = ":"),
            ),
            // Row 3: Z-,
            listOf(
                key("z", swipeUp = "!"), key("x", swipeUp = "\""), key("c", swipeUp = "'"),
                key("v", swipeUp = "/"),
                // Trackball area
                key("b", swipeUp = "\\"), key("n", swipeUp = "?"),
                key("m", swipeUp = "+"), key(",", label = ",", swipeUp = "<"),
            ),
            // Row 4: Modifiers
            listOf(
                KeyDef("Tab", action = KeyAction.Tab, widthMultiplier = 1.1f),
                KeyDef("Fn", action = KeyAction.Fn, widthMultiplier = 1.1f),
                KeyDef(" ", label = "⎵", action = KeyAction.Space, widthMultiplier = 2f),
                KeyDef("GL", action = KeyAction.SwitchIme),
                // Trackball area
                KeyDef("⌫", action = KeyAction.Backspace, widthMultiplier = 1.2f),
                KeyDef("↵", action = KeyAction.Enter, widthMultiplier = 1.2f),
                key(".", swipeUp = ">"),
            ),
        ),
    )

    val fnLayer1: KeyboardLayout = KeyboardLayout(
        rows = listOf(
            listOf(
                key("1"), key("2"), key("3"), key("4"), key("5"),
                key("6"), key("7"), key("8"), key("9"), key("0"),
            ),
            listOf(
                key("!"), key("@"), key("#"), key("$"), key("%"),
                key("^"), key("&"), key("*"), key("("), key(")"),
            ),
            listOf(
                KeyDef("C-c", action = KeyAction.KeyCode(android.view.KeyEvent.KEYCODE_C, ctrl = true)),
                KeyDef("C-d", action = KeyAction.KeyCode(android.view.KeyEvent.KEYCODE_D, ctrl = true)),
                KeyDef("C-z", action = KeyAction.KeyCode(android.view.KeyEvent.KEYCODE_Z, ctrl = true)),
                key("|"),
                KeyDef("←", action = KeyAction.KeyCode(android.view.KeyEvent.KEYCODE_DPAD_LEFT)),
                KeyDef("↓", action = KeyAction.KeyCode(android.view.KeyEvent.KEYCODE_DPAD_DOWN)),
                KeyDef("↑", action = KeyAction.KeyCode(android.view.KeyEvent.KEYCODE_DPAD_UP)),
                KeyDef("→", action = KeyAction.KeyCode(android.view.KeyEvent.KEYCODE_DPAD_RIGHT)),
            ),
            listOf(
                KeyDef("Esc", action = KeyAction.Escape, widthMultiplier = 1.1f),
                KeyDef("Fn2", action = KeyAction.FnPage2, widthMultiplier = 1.1f),
                KeyDef(" ", label = "⎵", action = KeyAction.Space, widthMultiplier = 2f),
                KeyDef("GL", action = KeyAction.SwitchIme),
                KeyDef("⌫", action = KeyAction.Backspace, widthMultiplier = 1.2f),
                KeyDef("↵", action = KeyAction.Enter, widthMultiplier = 1.2f),
                key("_"),
            ),
        ),
    )

    val fnLayer2: KeyboardLayout = KeyboardLayout(
        rows = listOf(
            listOf(
                KeyDef("F1", action = KeyAction.KeyCode(android.view.KeyEvent.KEYCODE_F1)),
                KeyDef("F2", action = KeyAction.KeyCode(android.view.KeyEvent.KEYCODE_F2)),
                KeyDef("F3", action = KeyAction.KeyCode(android.view.KeyEvent.KEYCODE_F3)),
                KeyDef("F4", action = KeyAction.KeyCode(android.view.KeyEvent.KEYCODE_F4)),
                KeyDef("F5", action = KeyAction.KeyCode(android.view.KeyEvent.KEYCODE_F5)),
                KeyDef("F6", action = KeyAction.KeyCode(android.view.KeyEvent.KEYCODE_F6)),
            ),
            listOf(
                KeyDef("F7", action = KeyAction.KeyCode(android.view.KeyEvent.KEYCODE_F7)),
                KeyDef("F8", action = KeyAction.KeyCode(android.view.KeyEvent.KEYCODE_F8)),
                KeyDef("F9", action = KeyAction.KeyCode(android.view.KeyEvent.KEYCODE_F9)),
                KeyDef("F10", action = KeyAction.KeyCode(android.view.KeyEvent.KEYCODE_F10)),
                KeyDef("F11", action = KeyAction.KeyCode(android.view.KeyEvent.KEYCODE_F11)),
                KeyDef("F12", action = KeyAction.KeyCode(android.view.KeyEvent.KEYCODE_F12)),
            ),
            listOf(
                KeyDef("Home", action = KeyAction.KeyCode(android.view.KeyEvent.KEYCODE_MOVE_HOME)),
                KeyDef("End", action = KeyAction.KeyCode(android.view.KeyEvent.KEYCODE_MOVE_END)),
                KeyDef("PgUp", action = KeyAction.KeyCode(android.view.KeyEvent.KEYCODE_PAGE_UP)),
                KeyDef("PgDn", action = KeyAction.KeyCode(android.view.KeyEvent.KEYCODE_PAGE_DOWN)),
                KeyDef("Ins", action = KeyAction.KeyCode(android.view.KeyEvent.KEYCODE_INSERT)),
                KeyDef("Del", action = KeyAction.KeyCode(android.view.KeyEvent.KEYCODE_FORWARD_DEL)),
            ),
            listOf(
                KeyDef("Esc", action = KeyAction.Escape, widthMultiplier = 1.1f),
                KeyDef("Fn", action = KeyAction.Fn, widthMultiplier = 1.1f),
                KeyDef(" ", label = "⎵", action = KeyAction.Space, widthMultiplier = 2f),
                KeyDef("GL", action = KeyAction.SwitchIme),
                KeyDef("⌫", action = KeyAction.Backspace, widthMultiplier = 1.2f),
                KeyDef("↵", action = KeyAction.Enter, widthMultiplier = 1.2f),
                key("`"),
            ),
        ),
    )

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
}
