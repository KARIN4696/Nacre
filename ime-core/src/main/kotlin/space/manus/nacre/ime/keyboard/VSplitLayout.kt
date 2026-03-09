package space.manus.nacre.ime.keyboard

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import space.manus.nacre.ime.NacreInputMethodService
import space.manus.nacre.ime.input.Layer
import space.manus.nacre.ime.trackball.TrackballView

private val KeyboardBackground = Color(0xFF0F0F23)

/**
 * V-split keyboard that renders the left and right halves at opposing angles,
 * creating an ergonomic split layout for wide screens and tablets.
 *
 * @param service the IME service instance
 * @param angle rotation angle in degrees (0-30, default 15).
 *              Left half rotates by +angle, right half by -angle.
 */
@Composable
fun VSplitKeyboardScreen(
    service: NacreInputMethodService,
    angle: Float = 8f,
) {
    val layerManager = service.layerManager
    val layout = layerManager.currentLayout()
    val rows = layout.rows

    // Clamp angle to safe range
    val clampedAngle = angle.coerceIn(0f, 30f)

    // SPEC: keyboard height <= 35% of screen height
    val screenHeightDp = LocalConfiguration.current.screenHeightDp.dp
    val maxKeyboardHeight = screenHeightDp * 0.35f

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(max = maxKeyboardHeight)
            .background(KeyboardBackground)
            .padding(horizontal = 4.dp, vertical = 2.dp),
    ) {
        // Candidate bar spans full width
        CandidateBar(service = service)

        // Layer indicator
        val showLayer = layerManager.currentLayer != Layer.Base
        val showJa = layerManager.isJapanese

        if (showLayer || showJa) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 8.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                if (showJa) {
                    Text(
                        text = "\u3042",
                        color = Color(0xFF00D4AA),
                        fontSize = 10.sp,
                    )
                } else {
                    Spacer(modifier = Modifier.width(1.dp))
                }
                if (showLayer) {
                    Text(
                        text = when (layerManager.currentLayer) {
                            Layer.Fn1 -> "Fn"
                            Layer.Fn2 -> "Fn2"
                            else -> ""
                        },
                        color = Color(0xFF00D4AA),
                        fontSize = 10.sp,
                    )
                }
            }
        }

        // V-split keyboard rows
        for ((rowIndex, row) in rows.withIndex()) {
            val mid = row.size / 2
            val leftKeys = row.subList(0, mid)
            val rightKeys = row.subList(mid, row.size)

            val showTrackball = rowIndex == 2

            VSplitRow(
                leftKeys = leftKeys,
                rightKeys = rightKeys,
                service = service,
                angle = clampedAngle,
                showTrackball = showTrackball,
            )
        }
    }
}

/**
 * A single keyboard row rendered in V-split configuration.
 * The left half is rotated clockwise and the right half counter-clockwise
 * around their respective inner edges, creating the V shape.
 */
@Composable
private fun VSplitRow(
    leftKeys: List<space.manus.nacre.config.KeyDef>,
    rightKeys: List<space.manus.nacre.config.KeyDef>,
    service: NacreInputMethodService,
    angle: Float,
    showTrackball: Boolean,
    rowIndex: Int = 0,
) {
    val rowHeight = if (showTrackball) 64.dp else 56.dp

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(rowHeight),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // Left half: rotate clockwise (positive angle)
        // Transform origin at right edge center so the split opens outward
        Row(
            modifier = Modifier
                .weight(1f)
                .graphicsLayer {
                    rotationZ = angle
                    transformOrigin = TransformOrigin(1f, 0.5f)
                    clip = false
                },
        ) {
            for ((colIndex, keyDef) in leftKeys.withIndex()) {
                KeyView(
                    keyDef = keyDef,
                    service = service,
                    modifier = Modifier.weight(keyDef.widthMultiplier),
                    row = rowIndex,
                    column = colIndex,
                )
            }
        }

        // Center: trackball or gap
        if (showTrackball) {
            Spacer(modifier = Modifier.width(4.dp))
            TrackballView(service = service, modifier = Modifier.size(64.dp))
            Spacer(modifier = Modifier.width(4.dp))
        } else {
            Spacer(modifier = Modifier.width(24.dp))
        }

        // Right half: rotate counter-clockwise (negative angle)
        // Transform origin at left edge center
        Row(
            modifier = Modifier
                .weight(1f)
                .graphicsLayer {
                    rotationZ = -angle
                    transformOrigin = TransformOrigin(0f, 0.5f)
                    clip = false
                },
        ) {
            val mid = leftKeys.size
            for ((colIndex, keyDef) in rightKeys.withIndex()) {
                KeyView(
                    keyDef = keyDef,
                    service = service,
                    modifier = Modifier.weight(keyDef.widthMultiplier),
                    row = rowIndex,
                    column = mid + colIndex,
                )
            }
        }
    }
}
