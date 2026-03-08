package space.manus.nacre.ime.trackball

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import space.manus.nacre.ime.NacreInputMethodService
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.roundToInt
import kotlin.math.sqrt

private val TrackballBg = Color(0xFF1A1A3E)
private val TrackballDot = Color(0xFF00D4AA)

@Composable
fun TrackballView(
    service: NacreInputMethodService,
    modifier: Modifier = Modifier,
) {
    val density = LocalDensity.current

    var dotX by remember { mutableFloatStateOf(0f) }
    var dotY by remember { mutableFloatStateOf(0f) }

    val stepThreshold = with(density) { 8.dp.toPx() }
    val maxDotOffset = with(density) { 24.dp.toPx() }
    val tapThreshold = with(density) { 4.dp.toPx() }
    val doubleTapTimeoutMs = 300L

    Box(
        modifier = modifier
            .clip(CircleShape)
            .background(TrackballBg)
            .pointerInput(Unit) {
                var lastTapTime = 0L

                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    val downTime = System.currentTimeMillis()

                    var totalDragX = 0f
                    var totalDragY = 0f
                    var accumulatedX = 0f
                    var accumulatedY = 0f
                    var wasDragged = false

                    while (true) {
                        val event = awaitPointerEvent()
                        val change = event.changes.firstOrNull() ?: break

                        if (change.pressed) {
                            val delta = change.positionChange()
                            totalDragX += delta.x
                            totalDragY += delta.y

                            if (abs(totalDragX) > tapThreshold || abs(totalDragY) > tapThreshold) {
                                wasDragged = true
                            }

                            if (wasDragged) {
                                val velocity = sqrt(delta.x * delta.x + delta.y * delta.y) /
                                    density.density * 60f
                                val gain = cdGain(velocity)

                                accumulatedX += delta.x * gain
                                accumulatedY += delta.y * gain

                                dotX = (dotX + delta.x).coerceIn(-maxDotOffset, maxDotOffset)
                                dotY = (dotY + delta.y).coerceIn(-maxDotOffset, maxDotOffset)

                                var dx = 0
                                var dy = 0
                                while (abs(accumulatedX) >= stepThreshold) {
                                    dx += if (accumulatedX > 0) 1 else -1
                                    accumulatedX -= if (accumulatedX > 0) stepThreshold else -stepThreshold
                                }
                                while (abs(accumulatedY) >= stepThreshold) {
                                    dy += if (accumulatedY > 0) 1 else -1
                                    accumulatedY -= if (accumulatedY > 0) stepThreshold else -stepThreshold
                                }
                                if (dx != 0 || dy != 0) {
                                    service.inputEngine.moveCursor(dx, dy)
                                }
                            }
                            change.consume()
                        } else {
                            change.consume()
                            break
                        }
                    }

                    dotX = 0f
                    dotY = 0f

                    if (!wasDragged) {
                        val now = System.currentTimeMillis()
                        if (now - lastTapTime < doubleTapTimeoutMs) {
                            // Double-tap: word selection
                            selectWordAtCursor(service)
                            lastTapTime = 0L
                        } else {
                            lastTapTime = now
                        }
                    }
                }
            },
        contentAlignment = Alignment.Center,
    ) {
        // Outer ring
        Box(
            modifier = Modifier
                .size(56.dp)
                .clip(CircleShape)
                .background(TrackballDot.copy(alpha = 0.2f)),
            contentAlignment = Alignment.Center,
        ) {
            // Pointer dot
            Box(
                modifier = Modifier
                    .offset { IntOffset(dotX.roundToInt(), dotY.roundToInt()) }
                    .size(8.dp)
                    .clip(CircleShape)
                    .background(TrackballDot),
            )
        }
    }
}

private fun selectWordAtCursor(service: NacreInputMethodService) {
    val ic = service.currentInputConnection ?: return
    val extracted = ic.getExtractedText(
        android.view.inputmethod.ExtractedTextRequest(), 0,
    ) ?: return
    val text = extracted.text?.toString() ?: return
    val pos = extracted.selectionStart
    if (pos < 0 || pos > text.length) return

    var start = pos
    var end = pos
    while (start > 0 && text[start - 1].isLetterOrDigit()) start--
    while (end < text.length && text[end].isLetterOrDigit()) end++
    if (start < end) {
        ic.setSelection(start, end)
    }
}

private fun cdGain(velocityDpPerSec: Float): Float {
    val gMin = 1.5f
    val gMax = 6.0f
    val k = 0.015f
    val vMid = 300f
    return gMin + (gMax - gMin) / (1f + exp(-k * (velocityDpPerSec - vMid)).toFloat())
}
