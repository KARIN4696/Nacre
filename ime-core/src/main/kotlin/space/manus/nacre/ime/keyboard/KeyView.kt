package space.manus.nacre.ime.keyboard

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
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
import androidx.compose.ui.platform.LocalDensity
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
private val KeyTextAction = Color(0xFF0F0F23)

@Composable
fun KeyView(
    keyDef: KeyDef,
    service: NacreInputMethodService,
    modifier: Modifier = Modifier,
) {
    var isPressed by remember { mutableStateOf(false) }
    val scale by animateFloatAsState(if (isPressed) 0.95f else 1f, label = "keyScale")
    val swipeThreshold = with(LocalDensity.current) { 12.dp.toPx() }

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

    var dragOffsetX by remember { mutableFloatStateOf(0f) }
    var dragOffsetY by remember { mutableFloatStateOf(0f) }
    var swiped by remember { mutableStateOf(false) }

    Box(
        modifier = modifier
            .height(52.dp)
            .padding(1.dp)
            .scale(scale)
            .clip(RoundedCornerShape(8.dp))
            .background(bgColor)
            .pointerInput(keyDef) {
                detectTapGestures(
                    onPress = {
                        isPressed = true
                        try { awaitRelease() } finally { isPressed = false }
                    },
                    onTap = {
                        if (!swiped) {
                            service.inputEngine.processKey(keyDef)
                        }
                        swiped = false
                    },
                    onLongPress = {
                        // Fn long press = Escape
                        if (keyDef.action is KeyAction.Fn) {
                            service.inputEngine.processAction(KeyAction.Escape)
                        } else if (keyDef.longPress != null) {
                            service.currentInputConnection?.commitText(keyDef.longPress, 1)
                        }
                    },
                )
            }
            .pointerInput(keyDef) {
                detectDragGestures(
                    onDragStart = {
                        dragOffsetX = 0f
                        dragOffsetY = 0f
                        swiped = false
                    },
                    onDrag = { _, dragAmount ->
                        dragOffsetX += dragAmount.x
                        dragOffsetY += dragAmount.y
                    },
                    onDragEnd = {
                        if (abs(dragOffsetX) > swipeThreshold || abs(dragOffsetY) > swipeThreshold) {
                            swiped = true
                            val direction = if (abs(dragOffsetX) > abs(dragOffsetY)) {
                                if (dragOffsetX > 0) SwipeDirection.Right else SwipeDirection.Left
                            } else {
                                if (dragOffsetY > 0) SwipeDirection.Down else SwipeDirection.Up
                            }
                            service.inputEngine.processSwipe(keyDef, direction)
                        }
                    },
                )
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
