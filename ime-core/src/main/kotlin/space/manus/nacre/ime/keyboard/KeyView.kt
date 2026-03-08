package space.manus.nacre.ime.keyboard

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
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
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import space.manus.nacre.config.KeyAction
import space.manus.nacre.config.KeyDef
import space.manus.nacre.ime.NacreInputMethodService
import space.manus.nacre.ime.input.SwipeDirection
import kotlin.math.abs

private val KeyBg = Color(0xFF2A2A4A)
private val KeyBgPressed = Color(0xFF3A3A5A)
private val KeyBgFn = Color(0xFF1E3A5A)
private val KeyBgAction = Color(0xFF00D4AA)
private val KeyText = Color(0xFFE0E0E0)
private val KeyTextSwipe = Color(0xFF6666AA)

@Composable
fun KeyView(
    keyDef: KeyDef,
    service: NacreInputMethodService,
    modifier: Modifier = Modifier,
) {
    var isPressed by remember { mutableStateOf(false) }
    val scale by animateFloatAsState(if (isPressed) 0.95f else 1f, label = "keyScale")
    val swipeThresholdPx = with(LocalDensity.current) { 12.dp.toPx() }

    val isActionKey = keyDef.action is KeyAction.Backspace ||
        keyDef.action is KeyAction.Enter ||
        keyDef.action is KeyAction.Tab

    val isFnKey = keyDef.action is KeyAction.Fn ||
        keyDef.action is KeyAction.FnPage2

    val bgColor = when {
        isPressed -> KeyBgPressed
        isActionKey -> KeyBgAction.copy(alpha = 0.3f)
        isFnKey -> KeyBgFn
        else -> KeyBg
    }

    val textColor = if (isActionKey) KeyBgAction else KeyText

    Box(
        modifier = modifier
            .height(52.dp)
            .padding(1.dp)
            .scale(scale)
            .clip(RoundedCornerShape(8.dp))
            .background(bgColor)
            .semantics {
                contentDescription = when (keyDef.action) {
                    is KeyAction.Backspace -> "Backspace"
                    is KeyAction.Enter -> "Enter"
                    is KeyAction.Space -> "Space"
                    is KeyAction.Tab -> "Tab"
                    is KeyAction.Escape -> "Escape"
                    is KeyAction.Fn -> "Function"
                    is KeyAction.FnPage2 -> "Function page 2"
                    is KeyAction.SwitchIme -> "Switch keyboard"
                    is KeyAction.Shift -> "Shift"
                    is KeyAction.ToggleJapanese -> "Toggle Japanese"
                    else -> keyDef.label
                }
            }
            .pointerInput(keyDef) {
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    isPressed = true
                    val downTime = System.currentTimeMillis()

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

                    val holdDuration = System.currentTimeMillis() - downTime

                    when {
                        wasDragged -> {
                            val direction = if (abs(totalDragX) > abs(totalDragY)) {
                                if (totalDragX > 0) SwipeDirection.Right else SwipeDirection.Left
                            } else {
                                if (totalDragY > 0) SwipeDirection.Down else SwipeDirection.Up
                            }
                            service.inputEngine.processSwipe(keyDef, direction)
                        }
                        holdDuration >= 350L -> {
                            // Long press — route through InputEngine
                            if (keyDef.action is KeyAction.Fn) {
                                service.inputEngine.processAction(KeyAction.Escape)
                            } else if (keyDef.longPress != null) {
                                service.inputEngine.processAction(KeyAction.Text(keyDef.longPress!!))
                            }
                        }
                        else -> {
                            // Tap
                            service.inputEngine.processKey(keyDef)
                        }
                    }
                }
            },
        contentAlignment = Alignment.Center,
    ) {
        // Main label
        Text(
            text = if (service.layerManager.isShifted && keyDef.primary.length == 1) {
                keyDef.label.uppercase()
            } else {
                keyDef.label
            },
            color = textColor,
            fontSize = if (keyDef.label.length > 2) 12.sp else 16.sp,
            textAlign = TextAlign.Center,
        )

        // Swipe hints
        if (keyDef.swipeUp != null) {
            Text(
                text = keyDef.swipeUp!!,
                color = KeyTextSwipe,
                fontSize = 9.sp,
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(end = 3.dp, top = 1.dp),
            )
        }
    }
}
