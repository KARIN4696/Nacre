package space.manus.nacre.ime.keyboard

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import space.manus.nacre.config.ThemeProvider
import space.manus.nacre.ime.NacreInputMethodService
import space.manus.nacre.ime.input.Layer
import space.manus.nacre.ime.trackball.TrackballView

@Composable
fun KeyboardScreen(service: NacreInputMethodService) {
    val layoutMode = service.layoutSelector.selectLayout()

    // Animate lighting tick (updates every frame when lighting is active)
    val lighting = service.keyLighting
    if (lighting.mode != KeyLighting.Mode.OFF) {
        LaunchedEffect(lighting.mode) {
            while (true) {
                lighting.animationTick = System.currentTimeMillis()
                delay(50L) // 20fps for lighting
            }
        }
    }

    // Panel state
    var showClipboard by remember { mutableStateOf(false) }
    var showCommandPalette by remember { mutableStateOf(false) }

    // Check if command palette should open (Fn+Space triggers it)
    val isCommandPaletteRequested = service.layerManager.isCommandPaletteRequested
    LaunchedEffect(isCommandPaletteRequested) {
        if (isCommandPaletteRequested) {
            showCommandPalette = true
            service.layerManager.isCommandPaletteRequested = false
        }
    }

    // Overlay panels take over the whole view
    if (showClipboard) {
        ClipboardPanel(service = service, onDismiss = { showClipboard = false })
        return
    }
    if (showCommandPalette) {
        CommandPalette(service = service, onDismiss = { showCommandPalette = false })
        return
    }

    when (layoutMode) {
        space.manus.nacre.ime.foldable.LayoutMode.FullVSplit ->
            VSplitKeyboardScreen(service = service)
        else ->
            StandardKeyboardScreen(service = service)
    }
}

@Composable
private fun StandardKeyboardScreen(service: NacreInputMethodService) {
    val layerManager = service.layerManager
    val layout = layerManager.currentLayout()
    val theme = remember { ThemeProvider.loadSelectedTheme(service) }
    val bgColor = Color(theme.background.toInt())
    val accentColor = Color(theme.accent.toInt())

    // SPEC: keyboard height <= 35% of screen height
    val screenHeightDp = LocalConfiguration.current.screenHeightDp.dp
    val maxKeyboardHeight = screenHeightDp * 0.35f

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(max = maxKeyboardHeight)
            .background(bgColor)
            .padding(horizontal = 4.dp, vertical = 2.dp),
    ) {
        // Candidate bar (prediction/conversion)
        CandidateBar(service = service)

        // Status bar: layer + Japanese mode + shift + voice indicators
        StatusBar(service = service, layerManager = layerManager, accentColor = accentColor)

        // Keyboard rows with trackball in the middle
        val rows = layout.rows
        for ((rowIndex, row) in rows.withIndex()) {
            if (rowIndex == 2 || rowIndex == 3) {
                KeyRowWithTrackball(
                    keys = row,
                    service = service,
                    showTrackball = rowIndex == 2,
                    rowIndex = rowIndex,
                )
            } else {
                KeyRow(keys = row, service = service, rowIndex = rowIndex)
            }
        }
    }
}

@Composable
private fun StatusBar(
    service: NacreInputMethodService,
    layerManager: space.manus.nacre.ime.input.LayerManager,
    accentColor: Color,
) {
    val showLayer = layerManager.currentLayer != Layer.Base
    val showJa = layerManager.isJapanese
    val showShift = layerManager.isShifted
    val voiceManager = service.voiceInputManager
    val isListening = voiceManager.isListening

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 8.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            if (showJa) {
                Text(text = "あ", color = accentColor, fontSize = 10.sp)
            }
            if (showShift) {
                Text(text = "⇧", color = accentColor, fontSize = 10.sp)
            }
        }

        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            // Voice input indicator / partial text
            if (isListening) {
                Text(
                    text = voiceManager.partialText.ifEmpty { "🎤 …" },
                    color = Color(0xFFFF4444),
                    fontSize = 10.sp,
                )
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
    rowIndex: Int = 0,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(56.dp),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        for ((colIndex, keyDef) in keys.withIndex()) {
            KeyView(
                keyDef = keyDef,
                service = service,
                modifier = Modifier.weight(keyDef.widthMultiplier),
                row = rowIndex,
                column = colIndex,
            )
        }
    }
}

@Composable
fun KeyRowWithTrackball(
    keys: List<space.manus.nacre.config.KeyDef>,
    service: NacreInputMethodService,
    showTrackball: Boolean,
    rowIndex: Int = 0,
) {
    val mid = keys.size / 2
    val leftKeys = keys.subList(0, mid)
    val rightKeys = keys.subList(mid, keys.size)

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(if (showTrackball) 64.dp else 56.dp),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Row(modifier = Modifier.weight(1f)) {
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

        if (showTrackball) {
            // SPEC: >=8dp deadzone between trackball and adjacent V/B keys
            Spacer(modifier = Modifier.width(4.dp))
            TrackballView(service = service, modifier = Modifier.size(76.dp))
            Spacer(modifier = Modifier.width(4.dp))
        } else {
            Spacer(modifier = Modifier.width(84.dp)) // 76 + 8dp deadzone
        }

        Row(modifier = Modifier.weight(1f)) {
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
