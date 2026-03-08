package space.manus.nacre.ime.trackball

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
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
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import space.manus.nacre.ime.NacreInputMethodService
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.roundToInt

private val TrackballBg = Color(0xFF1A1A3E)
private val TrackballBorder = Color(0xFF00D4AA)
private val TrackballDot = Color(0xFF00D4AA)

/**
 * Central trackball — CDGain acceleration curve.
 *
 * G(v) = G_min + (G_max - G_min) / (1 + exp(-k * (v - v_mid)))
 * G_min=1.5, G_max=6.0, k=0.015, v_mid=300
 */
@Composable
fun TrackballView(
    service: NacreInputMethodService,
    modifier: Modifier = Modifier,
) {
    val density = LocalDensity.current

    // Pointer dot position (visual feedback)
    var dotX by remember { mutableFloatStateOf(0f) }
    var dotY by remember { mutableFloatStateOf(0f) }

    // Accumulated movement for step-based cursor firing
    var accumulatedX by remember { mutableFloatStateOf(0f) }
    var accumulatedY by remember { mutableFloatStateOf(0f) }

    // Step threshold in pixels (1 character worth of movement)
    val stepThreshold = with(density) { 8.dp.toPx() }
    val dotRadius = with(density) { 4.dp.toPx() }
    val maxDotOffset = with(density) { 24.dp.toPx() }

    Box(
        modifier = modifier
            .clip(CircleShape)
            .background(TrackballBg)
            .pointerInput(Unit) {
                detectTapGestures(
                    onTap = {
                        // Tap on trackball = tap action in the focused field
                        // (can be used for confirming selection, etc.)
                    },
                    onDoubleTap = {
                        // Double-tap = word selection
                        val ic = service.currentInputConnection ?: return@detectTapGestures
                        // Select word at cursor
                        val extracted = ic.getExtractedText(
                            android.view.inputmethod.ExtractedTextRequest(), 0,
                        ) ?: return@detectTapGestures
                        val text = extracted.text.toString()
                        val pos = extracted.selectionStart
                        if (pos < 0 || pos > text.length) return@detectTapGestures

                        var start = pos
                        var end = pos
                        while (start > 0 && text[start - 1].isLetterOrDigit()) start--
                        while (end < text.length && text[end].isLetterOrDigit()) end++
                        if (start < end) {
                            ic.setSelection(start, end)
                        }
                    },
                    onLongPress = {
                        // Long press = copy/paste menu
                        // TODO: Show clipboard popup
                    },
                )
            }
            .pointerInput(Unit) {
                detectDragGestures(
                    onDragStart = {
                        accumulatedX = 0f
                        accumulatedY = 0f
                        dotX = 0f
                        dotY = 0f
                    },
                    onDrag = { _, dragAmount ->
                        // Calculate velocity-based CDGain
                        val velocity = kotlin.math.sqrt(
                            dragAmount.x * dragAmount.x + dragAmount.y * dragAmount.y,
                        ) / density.density // dp/frame → approximate dp/s at 60fps
                        val velocityDpPerSec = velocity * 60f

                        val gain = cdGain(velocityDpPerSec)

                        accumulatedX += dragAmount.x * gain
                        accumulatedY += dragAmount.y * gain

                        // Visual dot feedback (clamped to trackball area)
                        dotX = (dotX + dragAmount.x).coerceIn(-maxDotOffset, maxDotOffset)
                        dotY = (dotY + dragAmount.y).coerceIn(-maxDotOffset, maxDotOffset)

                        // Fire cursor steps when accumulated movement exceeds threshold
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
                    },
                    onDragEnd = {
                        dotX = 0f
                        dotY = 0f
                    },
                )
            },
        contentAlignment = Alignment.Center,
    ) {
        // Outer ring
        Box(
            modifier = Modifier
                .size(56.dp)
                .clip(CircleShape)
                .background(TrackballBorder.copy(alpha = 0.2f)),
            contentAlignment = Alignment.Center,
        ) {
            // Pointer dot
            Box(
                modifier = Modifier
                    .offset {
                        IntOffset(dotX.roundToInt(), dotY.roundToInt())
                    }
                    .size(8.dp)
                    .clip(CircleShape)
                    .background(TrackballDot),
            )
        }
    }
}

/**
 * CDGain sigmoid acceleration curve.
 *
 * G(v) = G_min + (G_max - G_min) / (1 + exp(-k * (v - v_mid)))
 */
private fun cdGain(velocityDpPerSec: Float): Float {
    val gMin = 1.5f
    val gMax = 6.0f
    val k = 0.015f
    val vMid = 300f

    return gMin + (gMax - gMin) / (1f + exp(-k * (velocityDpPerSec - vMid)).toFloat())
}
