package space.manus.nacre.ime.keyboard

import androidx.compose.runtime.Composable
import space.manus.nacre.config.KeyAction
import space.manus.nacre.config.KeyDef
import space.manus.nacre.ime.NacreInputMethodService

/**
 * Simple 4x3 number pad for the 12-key flick layout's "123" tab.
 * Reuses KeyRow and KeyView for consistent styling and lighting.
 */
@Composable
fun NumberPad(service: NacreInputMethodService) {
    val rows = listOf(
        listOf(numKey("1"), numKey("2"), numKey("3")),
        listOf(numKey("4"), numKey("5"), numKey("6")),
        listOf(numKey("7"), numKey("8"), numKey("9")),
        listOf(
            KeyDef("*", label = "* #", swipeRight = "#"),
            numKey("0"),
            KeyDef("⌫", action = KeyAction.Backspace),
        ),
    )
    for ((rowIndex, row) in rows.withIndex()) {
        KeyRow(keys = row, service = service, rowIndex = rowIndex, keyHeightDp = 52f)
    }
}

private fun numKey(digit: String) = KeyDef(digit, action = KeyAction.Text(digit))
