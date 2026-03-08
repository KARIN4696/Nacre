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
import space.manus.nacre.config.ThemeProvider
import space.manus.nacre.ime.NacreInputMethodService
import space.manus.nacre.ime.input.Layer
import space.manus.nacre.ime.trackball.TrackballView

@Composable
fun KeyboardScreen(service: NacreInputMethodService) {
    val layerManager = service.layerManager
    val layout = layerManager.currentLayout()
    val theme = ThemeProvider.loadSelectedTheme(service)
    val bgColor = Color(theme.background)
    val accentColor = Color(theme.accent)

    // Panel state
    var showClipboard by remember { mutableStateOf(false) }
    var showCommandPalette by remember { mutableStateOf(false) }

    // Check if command palette should open (Fn+Space triggers it)
    val isCommandPaletteRequested = layerManager.isCommandPaletteRequested
    LaunchedEffect(isCommandPaletteRequested) {
        if (isCommandPaletteRequested) {
            showCommandPalette = true
            layerManager.isCommandPaletteRequested = false
        }
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(bgColor)
            .padding(horizontal = 4.dp, vertical = 2.dp),
    ) {
        // Overlay panels
        if (showClipboard) {
            ClipboardPanel(service = service, onDismiss = { showClipboard = false })
            return@Column
        }
        if (showCommandPalette) {
            CommandPalette(service = service, onDismiss = { showCommandPalette = false })
            return@Column
        }

        // Candidate bar (prediction/conversion)
        CandidateBar(service = service)

        // Status bar: layer + Japanese mode + shift indicators
        StatusBar(layerManager = layerManager, accentColor = accentColor)

        // Keyboard rows with trackball in the middle
        val rows = layout.rows
        for ((rowIndex, row) in rows.withIndex()) {
            if (rowIndex == 2 || rowIndex == 3) {
                KeyRowWithTrackball(
                    keys = row,
                    service = service,
                    showTrackball = rowIndex == 2,
                )
            } else {
                KeyRow(keys = row, service = service)
            }
        }
    }
}

@Composable
private fun StatusBar(
    layerManager: space.manus.nacre.ime.input.LayerManager,
    accentColor: Color,
) {
    val showLayer = layerManager.currentLayer != Layer.Base
    val showJa = layerManager.isJapanese
    val showShift = layerManager.isShifted

    if (showLayer || showJa || showShift) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 8.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                if (showJa) {
                    Text(text = "あ", color = accentColor, fontSize = 10.sp)
                }
                if (showShift) {
                    Text(text = "⇧", color = accentColor, fontSize = 10.sp)
                }
            }
            if (showLayer) {
                Text(
                    text = when (layerManager.currentLayer) {
                        Layer.Fn1 -> "Fn"
                        Layer.Fn2 -> "Fn2"
                        else -> ""
                    },
                    color = accentColor,
                    fontSize = 10.sp,
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
        Row(modifier = Modifier.weight(1f)) {
            for (keyDef in leftKeys) {
                KeyView(
                    keyDef = keyDef,
                    service = service,
                    modifier = Modifier.weight(keyDef.widthMultiplier),
                )
            }
        }

        if (showTrackball) {
            TrackballView(service = service, modifier = Modifier.size(60.dp))
        } else {
            Spacer(modifier = Modifier.width(60.dp))
        }

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
