package space.manus.nacre.ime.keyboard

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import space.manus.nacre.config.KeyAction
import space.manus.nacre.config.KeyDef
import space.manus.nacre.ime.NacreInputMethodService
import space.manus.nacre.ime.input.FlickEngine
import kotlin.math.abs

/**
 * 4×3 grid of symbol keys with flick-for-alternatives.
 * 11 symbol keys from FlickEngine.symbolKeys + 1 backspace key.
 * Symbols are committed directly via InputConnection (no composing state).
 */
@Composable
fun SymbolFlickPad(
    service: NacreInputMethodService,
    modifier: Modifier = Modifier,
) {
    // 11 symbol keys + 1 backspace = 12 keys total, arranged 4 rows × 3 cols
    val symbolKeys = FlickEngine.symbolKeys // 11 entries
    val keyHeight = 52.dp
    val shape = RoundedCornerShape(6.dp)
    val swipeThresholdPx = with(LocalDensity.current) { 12.dp.toPx() }

    Column(modifier = modifier.fillMaxWidth()) {
        // Row index 0..3, column index 0..2
        for (row in 0 until 4) {
            Row(modifier = Modifier.fillMaxWidth()) {
                for (col in 0 until 3) {
                    val cellIndex = row * 3 + col
                    if (cellIndex < 11) {
                        // Symbol flick key
                        val symKey = symbolKeys[cellIndex]
                        SymbolKey(
                            symKey = symKey,
                            service = service,
                            shape = shape,
                            swipeThresholdPx = swipeThresholdPx,
                            modifier = Modifier
                                .weight(1f)
                                .height(keyHeight),
                        )
                    } else {
                        // Cell index 11 → backspace key (last slot, row 3 col 2)
                        KeyView(
                            keyDef = KeyDef("⌫", action = KeyAction.Backspace),
                            service = service,
                            modifier = Modifier
                                .weight(1f)
                                .height(keyHeight),
                            row = row,
                            column = col,
                            heightDp = 0f, // height managed by Modifier.height above
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun SymbolKey(
    symKey: FlickEngine.SymbolFlickKey,
    service: NacreInputMethodService,
    shape: RoundedCornerShape,
    swipeThresholdPx: Float,
    modifier: Modifier = Modifier,
) {
    var isPressed by remember { mutableStateOf(false) }
    val scale by animateFloatAsState(
        targetValue = if (isPressed) 0.95f else 1f,
        label = "symKeyScale",
    )

    // Theme colors
    val theme = service.currentTheme
    val keyBg = Color(theme.keyBackground.toInt())
    val keyBgPressed = Color(theme.keyBackgroundPressed.toInt())
    val keyText = Color(theme.keyText.toInt())
    val keyTextSwipe = Color(theme.keyTextSwipe.toInt())
    val glowColor = Color(theme.accent.toInt())
    val keyEdgeLight = Color(theme.surface.toInt())

    val bgColor = if (isPressed) keyBgPressed else keyBg
    val textColor = if (isPressed) glowColor else keyText

    val glowAlpha by animateFloatAsState(
        targetValue = if (isPressed) 0.8f else 0f,
        animationSpec = tween(durationMillis = if (isPressed) 30 else 200),
        label = "symGlow",
    )
    val borderColor = if (isPressed) glowColor.copy(alpha = glowAlpha)
    else Color.Transparent

    Box(
        modifier = modifier
            .padding(horizontal = 2.dp, vertical = 1.5.dp)
            .scale(scale)
            .clip(shape)
            // Outer shell — bottom edge highlight for mechanical depth
            .background(if (isPressed) keyBgPressed else keyEdgeLight)
            .padding(bottom = 1.5.dp)
            .clip(shape)
            .background(bgColor)
            .then(
                if (borderColor != Color.Transparent) {
                    Modifier.border(width = 1.dp, color = borderColor, shape = shape)
                } else {
                    Modifier.border(
                        width = 0.5.dp,
                        color = keyEdgeLight.copy(alpha = 0.5f),
                        shape = shape,
                    )
                },
            )
            .pointerInput(symKey) {
                awaitEachGesture {
                    awaitFirstDown(requireUnconsumed = false)
                    isPressed = true

                    var totalDragX = 0f
                    var totalDragY = 0f
                    var wasDragged = false

                    try {
                        while (true) {
                            val event = awaitPointerEvent()
                            val change = event.changes.firstOrNull() ?: break
                            if (change.pressed) {
                                val delta = change.positionChange()
                                totalDragX += delta.x
                                totalDragY += delta.y
                                if (abs(totalDragX) > swipeThresholdPx ||
                                    abs(totalDragY) > swipeThresholdPx
                                ) {
                                    wasDragged = true
                                }
                                change.consume()
                            } else {
                                change.consume()
                                break
                            }
                        }
                    } finally {
                        isPressed = false
                    }

                    // Determine flick direction
                    val direction = if (wasDragged) {
                        if (abs(totalDragX) > abs(totalDragY)) {
                            if (totalDragX > 0) FlickEngine.Direction.Right else FlickEngine.Direction.Left
                        } else {
                            if (totalDragY > 0) FlickEngine.Direction.Down else FlickEngine.Direction.Up
                        }
                    } else {
                        FlickEngine.Direction.Tap
                    }

                    val resolved = FlickEngine.resolveSymbolFlick(symKey, direction)
                    if (resolved != null) {
                        // Haptic feedback
                        if (wasDragged) {
                            service.feedbackManager.onSwipe()
                        } else {
                            service.feedbackManager.onKeyPress(KeyAction.Text(resolved))
                        }
                        // Commit directly — symbols bypass composing state
                        val ic = service.currentInputConnection
                        ic?.commitText(resolved, 1)
                    }
                }
            },
        contentAlignment = Alignment.Center,
    ) {
        // Main label (tap character)
        Text(
            text = symKey.tap,
            color = textColor,
            fontSize = 15.sp,
            fontWeight = FontWeight.Medium,
            fontFamily = FontFamily.Monospace,
            textAlign = TextAlign.Center,
            style = if (isPressed) {
                androidx.compose.ui.text.TextStyle(
                    shadow = androidx.compose.ui.graphics.Shadow(
                        color = glowColor,
                        blurRadius = 12f,
                    ),
                )
            } else {
                androidx.compose.ui.text.TextStyle.Default
            },
        )

        // Swipe-up hint (show the 'up' alternative if present)
        if (symKey.up != null) {
            Text(
                text = symKey.up,
                color = keyTextSwipe,
                fontSize = 9.sp,
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(end = 3.dp, top = 1.dp),
            )
        }
    }
}
