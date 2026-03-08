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
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupProperties
import kotlinx.coroutines.withTimeoutOrNull
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
private val GlowColor = Color(0xFF00D4AA)

@Composable
fun KeyView(
    keyDef: KeyDef,
    service: NacreInputMethodService,
    modifier: Modifier = Modifier,
    row: Int = 0,
    column: Int = 0,
) {
    var isPressed by remember { mutableStateOf(false) }
    var showLongPressPopup by remember { mutableStateOf(false) }
    var longPressChar by remember { mutableStateOf("") }
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

    // Mechanical glow effect — combines press glow + lighting engine
    val lighting = service.keyLighting
    val lightingColor = lighting.getKeyColor(keyDef.primary, row, column)

    val glowAlpha by animateFloatAsState(
        targetValue = if (isPressed) 0.8f else 0f,
        animationSpec = tween(durationMillis = if (isPressed) 30 else 200),
        label = "glow",
    )
    val pressGlow = if (isPressed) GlowColor.copy(alpha = glowAlpha) else Color.Transparent
    // Merge press glow with lighting engine color
    val borderColor = if (pressGlow != Color.Transparent) pressGlow
        else if (lightingColor != Color.Transparent) lightingColor
        else Color.Transparent

    val shape = RoundedCornerShape(8.dp)

    Box(
        modifier = modifier
            .height(52.dp)
            .padding(1.dp)
            .scale(scale)
            .clip(shape)
            .background(bgColor)
            .then(
                if (borderColor != Color.Transparent) {
                    Modifier.border(
                        width = 1.5.dp,
                        color = borderColor,
                        shape = shape,
                    )
                } else {
                    Modifier
                }
            )
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

                    val isFnAction = keyDef.action is KeyAction.Fn
                    if (isFnAction) {
                        service.layerManager.fnDown()
                    }

                    var totalDragX = 0f
                    var totalDragY = 0f
                    var wasDragged = false
                    var longPressHandled = false

                    try {
                        while (true) {
                            val event = withTimeoutOrNull(
                                if (longPressHandled) Long.MAX_VALUE
                                else maxOf(1L, 350L - (System.currentTimeMillis() - downTime)),
                            ) {
                                awaitPointerEvent()
                            }

                            if (event == null) {
                                // Timeout = long press threshold reached while finger is still down
                                if (!wasDragged && !longPressHandled) {
                                    longPressHandled = true
                                    val feedback = service.feedbackManager
                                    feedback.onLongPress()
                                    if (keyDef.action is KeyAction.Fn) {
                                        service.inputEngine.processAction(KeyAction.Escape)
                                    } else if (keyDef.longPress != null) {
                                        // SPEC: Gboard-style popup bubble for long-press character
                                        longPressChar = keyDef.longPress!!
                                        showLongPressPopup = true
                                        service.inputEngine.processAction(KeyAction.Text(keyDef.longPress!!))
                                    }
                                }
                                continue
                            }

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
                        showLongPressPopup = false
                    }

                    val feedback = service.feedbackManager

                    val holdDuration = System.currentTimeMillis() - downTime

                    // Fn key: handle hold vs tap
                    if (isFnAction) {
                        if (longPressHandled) {
                            // Long press = Esc already handled, return to base
                            service.layerManager.fnUp(wasTap = false)
                        } else if (wasDragged) {
                            service.layerManager.fnUp(wasTap = false)
                            val direction = if (abs(totalDragX) > abs(totalDragY)) {
                                if (totalDragX > 0) SwipeDirection.Right else SwipeDirection.Left
                            } else {
                                if (totalDragY > 0) SwipeDirection.Down else SwipeDirection.Up
                            }
                            feedback.onSwipe()
                            service.inputEngine.processSwipe(keyDef, direction)
                        } else {
                            // Short tap (<350ms): toggle Fn (fnDown already set Fn1)
                            // fnUp(wasTap=true) keeps Fn1
                            service.layerManager.fnUp(wasTap = true)
                            feedback.onKeyPress(keyDef.action)
                            feedback.onLayerChange()
                        }
                    } else when {
                        wasDragged -> {
                            val direction = if (abs(totalDragX) > abs(totalDragY)) {
                                if (totalDragX > 0) SwipeDirection.Right else SwipeDirection.Left
                            } else {
                                if (totalDragY > 0) SwipeDirection.Down else SwipeDirection.Up
                            }
                            feedback.onSwipe()
                            service.inputEngine.processSwipe(keyDef, direction)
                        }
                        longPressHandled -> {
                            // Already handled during hold — do nothing on release
                        }
                        else -> {
                            feedback.onKeyPress(keyDef.action)
                            lighting.onKeyPress(keyDef.primary, column)
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

        // SPEC: Gboard-style popup bubble for long-press characters
        if (showLongPressPopup && longPressChar.isNotEmpty()) {
            Popup(
                alignment = Alignment.TopCenter,
                offset = androidx.compose.ui.unit.IntOffset(0, with(LocalDensity.current) { -56.dp.roundToPx() }),
                properties = PopupProperties(focusable = false),
            ) {
                Box(
                    modifier = Modifier
                        .size(48.dp, 48.dp)
                        .clip(RoundedCornerShape(8.dp))
                        .background(Color(0xFF3A3A6A)),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = longPressChar,
                        color = Color.White,
                        fontSize = 22.sp,
                        fontWeight = FontWeight.Bold,
                    )
                }
            }
        }

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
