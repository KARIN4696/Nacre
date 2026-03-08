package space.manus.nacre.ime.keyboard

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import space.manus.nacre.ime.NacreInputMethodService
import space.manus.nacre.ime.input.Layer
import space.manus.nacre.ime.trackball.TrackballView

private val KeyboardBackground = Color(0xFF0F0F23)
private val LayerIndicatorColor = Color(0xFF00D4AA)

@Composable
fun KeyboardScreen(service: NacreInputMethodService) {
    val layerManager = service.layerManager
    val layout = layerManager.currentLayout()

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(KeyboardBackground)
            .padding(horizontal = 4.dp, vertical = 2.dp),
    ) {
        // Layer indicator
        if (layerManager.currentLayer != Layer.Base) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 8.dp),
                horizontalArrangement = Arrangement.End,
            ) {
                Text(
                    text = when (layerManager.currentLayer) {
                        Layer.Fn1 -> "Fn"
                        Layer.Fn2 -> "Fn2"
                        else -> ""
                    },
                    color = LayerIndicatorColor,
                    fontSize = 10.sp,
                )
            }
        }

        // Japanese mode indicator
        if (layerManager.isJapanese) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 8.dp),
                horizontalArrangement = Arrangement.Start,
            ) {
                Text(
                    text = "あ",
                    color = LayerIndicatorColor,
                    fontSize = 10.sp,
                )
            }
        }

        // Keyboard rows with trackball in the middle
        val rows = layout.rows

        for ((rowIndex, row) in rows.withIndex()) {
            if (rowIndex == 2 || rowIndex == 3) {
                // Rows 3 and 4 have trackball between left and right halves
                KeyRowWithTrackball(
                    keys = row,
                    service = service,
                    showTrackball = rowIndex == 2,
                )
            } else {
                KeyRow(
                    keys = row,
                    service = service,
                )
            }
        }
    }
}

@Composable
fun KeyRow(
    keys: List<space.manus.nacre.config.KeyDef>,
    service: NacreInputMethodService,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(52.dp),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        for (keyDef in keys) {
            KeyView(
                keyDef = keyDef,
                service = service,
                modifier = Modifier.weight(keyDef.widthMultiplier),
            )
        }
    }
}

@Composable
fun KeyRowWithTrackball(
    keys: List<space.manus.nacre.config.KeyDef>,
    service: NacreInputMethodService,
    showTrackball: Boolean,
) {
    val mid = keys.size / 2
    val leftKeys = keys.subList(0, mid)
    val rightKeys = keys.subList(mid, keys.size)

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(if (showTrackball) 60.dp else 52.dp),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // Left half
        Row(modifier = Modifier.weight(1f)) {
            for (keyDef in leftKeys) {
                KeyView(
                    keyDef = keyDef,
                    service = service,
                    modifier = Modifier.weight(keyDef.widthMultiplier),
                )
            }
        }

        // Trackball (center)
        if (showTrackball) {
            TrackballView(
                service = service,
                modifier = Modifier.size(60.dp),
            )
        } else {
            Spacer(modifier = Modifier.width(60.dp))
        }

        // Right half
        Row(modifier = Modifier.weight(1f)) {
            for (keyDef in rightKeys) {
                KeyView(
                    keyDef = keyDef,
                    service = service,
                    modifier = Modifier.weight(keyDef.widthMultiplier),
                )
            }
        }
    }
}
