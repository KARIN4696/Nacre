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
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupProperties
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeoutOrNull
import space.manus.nacre.config.KeyAction
import space.manus.nacre.config.KeyDef
import space.manus.nacre.ime.NacreInputMethodService
import space.manus.nacre.ime.input.DakutenType
import space.manus.nacre.ime.input.FlickEngine
import kotlin.math.abs

/** Tab identifiers for the flick input pad. */
private enum class FlickTab { Kana, Numbers, Symbols, Emoji }

/**
 * Main 12-key flick input pad composable.
 * Shows tab bar (あ / 123 / #+ / 😊), grid content, and bottom row.
 */
@Composable
fun FlickInputPad(service: NacreInputMethodService) {
    var activeTab by remember { mutableStateOf(FlickTab.Kana) }
    val theme = service.currentTheme
    val bgColor = Color(theme.background.toInt())

    // Emoji tab: delegate entirely to EmojiPanel
    if (activeTab == FlickTab.Emoji) {
        EmojiPanel(service = service, onDismiss = { activeTab = FlickTab.Kana })
        return
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(bgColor),
    ) {
        ToolbarOrCandidateBar(service = service)

        // Tab bar: あ / 123 / #+ / 😊
        FlickTabBar(
            activeTab = activeTab,
            onTabSelected = { activeTab = it },
            service = service,
        )

        // Grid content area
        when (activeTab) {
            FlickTab.Kana -> FlickKanaGrid(service = service)
            FlickTab.Numbers -> NumberPad(service = service)
            FlickTab.Symbols -> SymbolsPanel(service = service, onDismiss = { activeTab = FlickTab.Kana })
            FlickTab.Emoji -> { /* handled above */ }
        }

        // Bottom row: ▲▼ / Space / 変換 / Alt / Enter
        if (activeTab == FlickTab.Kana || activeTab == FlickTab.Numbers) {
            FlickBottomRow(service = service)
        }
    }
}

// ─────────────────────────────────────────────────────────────────
// Tab bar
// ─────────────────────────────────────────────────────────────────

@Composable
private fun FlickTabBar(
    activeTab: FlickTab,
    onTabSelected: (FlickTab) -> Unit,
    service: NacreInputMethodService,
) {
    val theme = service.currentTheme
    val accentColor = Color(theme.accent.toInt())
    val surfaceColor = Color(theme.surface.toInt())
    val keyTextColor = Color(theme.keyText.toInt())

    val tabs = listOf(
        FlickTab.Kana to "あ",
        FlickTab.Numbers to "123",
        FlickTab.Symbols to "#+",
        FlickTab.Emoji to "😊",
    )

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(32.dp)
            .background(surfaceColor),
        horizontalArrangement = Arrangement.SpaceEvenly,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        tabs.forEach { (tab, label) ->
            val isActive = activeTab == tab
            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxHeight()
                    .then(
                        if (isActive) Modifier.border(
                            width = 1.5.dp,
                            color = accentColor,
                            shape = RoundedCornerShape(0.dp),
                        ) else Modifier
                    )
                    .pointerInput(tab) {
                        awaitEachGesture {
                            awaitFirstDown(requireUnconsumed = false)
                            onTabSelected(tab)
                        }
                    },
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = label,
                    color = if (isActive) accentColor else keyTextColor,
                    fontSize = 13.sp,
                    fontWeight = if (isActive) FontWeight.Bold else FontWeight.Normal,
                    textAlign = TextAlign.Center,
                )
            }
        }
    }
}

// ─────────────────────────────────────────────────────────────────
// Kana 4×5 Gboard-style grid
// ─────────────────────────────────────────────────────────────────

/**
 * 4-row × 5-column Gboard-style grid of flick keys.
 * Side columns (0 and 4) contain function keys at 0.8× width.
 * Centre columns (1-3) contain the standard kana keys.
 *
 * Layout:
 *   [↩(0.8x)]  [あ]  [か]  [さ]  [⌫(0.8x)]
 *   [◀(0.8x)]  [た]  [な]  [は]  [▶(0.8x)]
 *   [☺記(0.8x)] [ま]  [や]  [ら]  [␣(0.8x)]
 *   [あa1(0.8x)] [゛゜] [わ] [？。！] [↵(1.2x)]
 */
@Composable
private fun FlickKanaGrid(service: NacreInputMethodService) {
    val kanaKeys = FlickEngine.kanaKeys // 10 keys: あかさたなはまやらわ

    // Side key definitions: (label, KeyDef) per row × side (left/right)
    val sideKeySizeWeight = 0.8f
    val enterSizeWeight = 0.8f

    // Row 1 side keys
    val row1Left  = KeyDef("↩", action = KeyAction.Escape, widthMultiplier = sideKeySizeWeight)
    val row1Right = KeyDef("⌫", action = KeyAction.Backspace, widthMultiplier = sideKeySizeWeight)
    // Row 2 side keys
    val row2Left  = KeyDef("◀", action = KeyAction.KeyCode(android.view.KeyEvent.KEYCODE_DPAD_LEFT), widthMultiplier = sideKeySizeWeight)
    val row2Right = KeyDef("▶", action = KeyAction.KeyCode(android.view.KeyEvent.KEYCODE_DPAD_RIGHT), widthMultiplier = sideKeySizeWeight)
    // Row 3 side keys
    val row3Left  = KeyDef("☺記", action = KeyAction.Emoji, widthMultiplier = sideKeySizeWeight)
    val row3Right = KeyDef("␣", action = KeyAction.Space, widthMultiplier = sideKeySizeWeight)
    // Row 4 side keys
    val row4Left  = KeyDef("あa1", action = KeyAction.ToggleJapanese, widthMultiplier = sideKeySizeWeight)
    val row4Right = KeyDef("↵", action = KeyAction.Enter, widthMultiplier = enterSizeWeight)

    // Punctuation flick key for row 4, col 3
    // Tap cycles: 、→。→！→？  Flick for direct access
    val punctKey = FlickEngine.FlickKey(
        id = "punct",
        label = "、。",
        tap = "、",
        left = "。",
        up = "？",
        right = "！",
        down = "…",
    )

    Column(modifier = Modifier.fillMaxWidth()) {
        // Row 1: ↩ | あ | か | さ | ⌫
        Row(
            modifier = Modifier.fillMaxWidth().height(48.dp),
            horizontalArrangement = Arrangement.SpaceEvenly,
        ) {
            KeyView(keyDef = row1Left, service = service, modifier = Modifier.weight(sideKeySizeWeight), row = 0, column = 0)
            FlickKeyView(flickKey = kanaKeys[0], service = service, modifier = Modifier.weight(1f), row = 0, column = 1) // あ
            FlickKeyView(flickKey = kanaKeys[1], service = service, modifier = Modifier.weight(1f), row = 0, column = 2) // か
            FlickKeyView(flickKey = kanaKeys[2], service = service, modifier = Modifier.weight(1f), row = 0, column = 3) // さ
            KeyView(keyDef = row1Right, service = service, modifier = Modifier.weight(sideKeySizeWeight), row = 0, column = 4)
        }
        // Row 2: ◀ | た | な | は | ▶
        Row(
            modifier = Modifier.fillMaxWidth().height(48.dp),
            horizontalArrangement = Arrangement.SpaceEvenly,
        ) {
            KeyView(keyDef = row2Left, service = service, modifier = Modifier.weight(sideKeySizeWeight), row = 1, column = 0)
            FlickKeyView(flickKey = kanaKeys[3], service = service, modifier = Modifier.weight(1f), row = 1, column = 1) // た
            FlickKeyView(flickKey = kanaKeys[4], service = service, modifier = Modifier.weight(1f), row = 1, column = 2) // な
            FlickKeyView(flickKey = kanaKeys[5], service = service, modifier = Modifier.weight(1f), row = 1, column = 3) // は
            KeyView(keyDef = row2Right, service = service, modifier = Modifier.weight(sideKeySizeWeight), row = 1, column = 4)
        }
        // Row 3: ☺記 | ま | や | ら | ␣
        Row(
            modifier = Modifier.fillMaxWidth().height(48.dp),
            horizontalArrangement = Arrangement.SpaceEvenly,
        ) {
            KeyView(keyDef = row3Left, service = service, modifier = Modifier.weight(sideKeySizeWeight), row = 2, column = 0)
            FlickKeyView(flickKey = kanaKeys[6], service = service, modifier = Modifier.weight(1f), row = 2, column = 1) // ま
            FlickKeyView(flickKey = kanaKeys[7], service = service, modifier = Modifier.weight(1f), row = 2, column = 2) // や
            FlickKeyView(flickKey = kanaKeys[8], service = service, modifier = Modifier.weight(1f), row = 2, column = 3) // ら
            KeyView(keyDef = row3Right, service = service, modifier = Modifier.weight(sideKeySizeWeight), row = 2, column = 4)
        }
        // Row 4: あa1 | ゛゜小 | わ | ？。！ | ↵
        Row(
            modifier = Modifier.fillMaxWidth().height(48.dp),
            horizontalArrangement = Arrangement.SpaceEvenly,
        ) {
            KeyView(keyDef = row4Left, service = service, modifier = Modifier.weight(sideKeySizeWeight), row = 3, column = 0)
            DakutenKeyView(service = service, modifier = Modifier.weight(1f), row = 3, column = 1)
            FlickKeyView(flickKey = kanaKeys[9], service = service, modifier = Modifier.weight(1f), row = 3, column = 2) // わ
            FlickKeyView(flickKey = punctKey, service = service, modifier = Modifier.weight(1f), row = 3, column = 3) // ？。！
            KeyView(keyDef = row4Right, service = service, modifier = Modifier.weight(enterSizeWeight), row = 3, column = 4)
        }
    }
}

// ─────────────────────────────────────────────────────────────────
// Individual flick key
// ─────────────────────────────────────────────────────────────────

@Composable
private fun FlickKeyView(
    flickKey: FlickEngine.FlickKey,
    service: NacreInputMethodService,
    modifier: Modifier = Modifier,
    row: Int = 0,
    column: Int = 0,
) {
    var isPressed by remember { mutableStateOf(false) }
    var flickDir by remember { mutableStateOf(FlickEngine.Direction.Tap) }
    var showPopup by remember { mutableStateOf(false) }
    var popupKana by remember { mutableStateOf("") }

    val scale by animateFloatAsState(
        targetValue = if (isPressed) 0.95f else 1f,
        animationSpec = tween(durationMillis = 30),
        label = "flickKeyScale",
    )

    val theme = service.currentTheme
    val keyBg = Color(theme.keyBackground.toInt())
    val keyBgPressed = Color(theme.keyBackgroundPressed.toInt())
    val keyText = Color(theme.keyText.toInt())
    val accentColor = Color(theme.accent.toInt())
    val surfaceColor = Color(theme.surface.toInt())

    val lighting = service.keyLighting
    val lightingColor = lighting.getKeyColor(flickKey.id, row, column)

    val glowAlpha by animateFloatAsState(
        targetValue = if (isPressed) 0.8f else 0f,
        animationSpec = tween(durationMillis = if (isPressed) 30 else 200),
        label = "flickGlow",
    )
    val pressGlow = if (isPressed) accentColor.copy(alpha = glowAlpha) else Color.Transparent
    val borderColor = when {
        pressGlow != Color.Transparent -> pressGlow
        lightingColor != Color.Transparent -> lightingColor
        else -> Color.Transparent
    }

    val shape = RoundedCornerShape(6.dp)
    val flickThresholdPx = with(LocalDensity.current) { 10.dp.toPx() }

    Box(
        modifier = modifier
            .padding(horizontal = 1.dp, vertical = 1.dp)
            .scale(scale)
            .clip(shape)
            .background(if (isPressed) keyBgPressed else surfaceColor)
            .padding(bottom = 1.5.dp)
            .clip(shape)
            .background(if (isPressed) keyBgPressed else keyBg)
            .then(
                if (borderColor != Color.Transparent) {
                    Modifier.border(width = 1.dp, color = borderColor, shape = shape)
                } else {
                    Modifier.border(
                        width = 0.5.dp,
                        color = surfaceColor.copy(alpha = 0.5f),
                        shape = shape,
                    )
                }
            )
            .pointerInput(flickKey) {
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    isPressed = true
                    flickDir = FlickEngine.Direction.Tap
                    popupKana = flickKey.tap
                    showPopup = true

                    var totalX = 0f
                    var totalY = 0f
                    var resolved = FlickEngine.Direction.Tap

                    try {
                        while (true) {
                            val event = withTimeoutOrNull(Long.MAX_VALUE) {
                                awaitPointerEvent()
                            } ?: break

                            val change = event.changes.firstOrNull() ?: break
                            if (change.pressed) {
                                val delta = change.positionChange()
                                totalX += delta.x
                                totalY += delta.y

                                // Resolve direction dynamically for popup
                                resolved = when {
                                    abs(totalX) < flickThresholdPx && abs(totalY) < flickThresholdPx ->
                                        FlickEngine.Direction.Tap
                                    abs(totalX) > abs(totalY) ->
                                        if (totalX > 0) FlickEngine.Direction.Right else FlickEngine.Direction.Left
                                    else ->
                                        if (totalY > 0) FlickEngine.Direction.Down else FlickEngine.Direction.Up
                                }
                                flickDir = resolved
                                popupKana = FlickEngine.resolveFlick(flickKey, resolved) ?: flickKey.tap
                                change.consume()
                            } else {
                                change.consume()
                                break
                            }
                        }
                    } finally {
                        isPressed = false
                        showPopup = false
                    }

                    // Commit resolved kana
                    val kana = FlickEngine.resolveFlick(flickKey, resolved)
                    if (kana != null) {
                        val isTap = resolved == FlickEngine.Direction.Tap
                        service.inputEngine.processFlickKana(kana, flickKeyId = flickKey.id, isFlickTap = isTap)
                        service.feedbackManager.onKeyPress(KeyAction.Text(kana))
                        lighting.onKeyPress(flickKey.id, column)
                    }
                }
            },
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = flickKey.label,
            color = if (isPressed) accentColor else keyText,
            fontSize = 18.sp,
            fontWeight = FontWeight.Medium,
            fontFamily = FontFamily.Default,
            textAlign = TextAlign.Center,
            style = if (isPressed) {
                androidx.compose.ui.text.TextStyle(
                    shadow = androidx.compose.ui.graphics.Shadow(
                        color = accentColor,
                        blurRadius = 12f,
                    ),
                )
            } else {
                androidx.compose.ui.text.TextStyle.Default
            },
        )

        // Sub-labels for flick directions (small hints in corners)
        flickKey.up?.let { hint ->
            Text(
                text = hint,
                color = keyText.copy(alpha = 0.5f),
                fontSize = 8.sp,
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .padding(top = 2.dp),
            )
        }

        // Flick popup: 48×48dp bubble above the key showing resolved kana
        if (showPopup && popupKana.isNotEmpty()) {
            val popupOffsetPx = with(LocalDensity.current) { -56.dp.roundToPx() }
            Popup(
                alignment = Alignment.TopCenter,
                offset = IntOffset(0, popupOffsetPx),
                properties = PopupProperties(focusable = false),
            ) {
                Box(
                    modifier = Modifier
                        .size(48.dp, 48.dp)
                        .clip(RoundedCornerShape(8.dp))
                        .background(accentColor.copy(alpha = 0.9f)),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = popupKana,
                        color = Color(0xFF000000),
                        fontSize = 22.sp,
                        fontWeight = FontWeight.Bold,
                    )
                }
            }
        }
    }
}

// ─────────────────────────────────────────────────────────────────
// Dakuten key (゛゜小)
// ─────────────────────────────────────────────────────────────────

/**
 * ゛゜小 key:
 *   Tap  → dakuten (゛)
 *   Up   → handakuten (゜)
 *   Down → small kana (小)
 */
@Composable
private fun DakutenKeyView(
    service: NacreInputMethodService,
    modifier: Modifier = Modifier,
    row: Int = 3,
    column: Int = 0,
) {
    var isPressed by remember { mutableStateOf(false) }
    var showPopup by remember { mutableStateOf(false) }
    var popupLabel by remember { mutableStateOf("゛") }

    val scale by animateFloatAsState(
        targetValue = if (isPressed) 0.95f else 1f,
        animationSpec = tween(durationMillis = 30),
        label = "dakutenScale",
    )

    val theme = service.currentTheme
    val keyBg = Color(theme.keyBackground.toInt())
    val keyBgPressed = Color(theme.keyBackgroundPressed.toInt())
    val keyText = Color(theme.keyText.toInt())
    val accentColor = Color(theme.accent.toInt())
    val surfaceColor = Color(theme.surface.toInt())

    val lighting = service.keyLighting
    val lightingColor = lighting.getKeyColor("゛", row, column)

    val glowAlpha by animateFloatAsState(
        targetValue = if (isPressed) 0.8f else 0f,
        animationSpec = tween(durationMillis = if (isPressed) 30 else 200),
        label = "dakutenGlow",
    )
    val pressGlow = if (isPressed) accentColor.copy(alpha = glowAlpha) else Color.Transparent
    val borderColor = when {
        pressGlow != Color.Transparent -> pressGlow
        lightingColor != Color.Transparent -> lightingColor
        else -> Color.Transparent
    }

    val shape = RoundedCornerShape(6.dp)
    val flickThresholdPx = with(LocalDensity.current) { 10.dp.toPx() }

    Box(
        modifier = modifier
            .padding(horizontal = 1.dp, vertical = 1.dp)
            .scale(scale)
            .clip(shape)
            .background(if (isPressed) keyBgPressed else surfaceColor)
            .padding(bottom = 1.5.dp)
            .clip(shape)
            .background(if (isPressed) keyBgPressed else keyBg)
            .then(
                if (borderColor != Color.Transparent) {
                    Modifier.border(width = 1.dp, color = borderColor, shape = shape)
                } else {
                    Modifier.border(
                        width = 0.5.dp,
                        color = surfaceColor.copy(alpha = 0.5f),
                        shape = shape,
                    )
                }
            )
            .pointerInput(Unit) {
                awaitEachGesture {
                    awaitFirstDown(requireUnconsumed = false)
                    isPressed = true
                    popupLabel = "゛"
                    showPopup = true

                    var totalX = 0f
                    var totalY = 0f
                    // Gboard style: tap=toggle dakuten/handakuten, up=small, left=handakuten
                    var resolved = DakutenType.Dakuten

                    try {
                        while (true) {
                            val event = withTimeoutOrNull(Long.MAX_VALUE) {
                                awaitPointerEvent()
                            } ?: break

                            val change = event.changes.firstOrNull() ?: break
                            if (change.pressed) {
                                val delta = change.positionChange()
                                totalX += delta.x
                                totalY += delta.y

                                resolved = when {
                                    abs(totalX) < flickThresholdPx && abs(totalY) < flickThresholdPx ->
                                        DakutenType.Dakuten // tap = toggle dakuten (が↔か)
                                    totalY < -flickThresholdPx -> DakutenType.Small // up = small (つ→っ)
                                    totalX < -flickThresholdPx -> DakutenType.Handakuten // left = handakuten (は→ぱ)
                                    else -> DakutenType.Dakuten
                                }
                                popupLabel = when (resolved) {
                                    DakutenType.Dakuten -> "゛"
                                    DakutenType.Handakuten -> "゜"
                                    DakutenType.Small -> "小"
                                }
                                change.consume()
                            } else {
                                change.consume()
                                break
                            }
                        }
                    } finally {
                        isPressed = false
                        showPopup = false
                    }

                    service.inputEngine.processFlickDakuten(resolved)
                    service.feedbackManager.onKeyPress(KeyAction.Text(popupLabel))
                    lighting.onKeyPress("゛", column)
                }
            },
        contentAlignment = Alignment.Center,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                text = "゛゜",
                color = if (isPressed) accentColor else keyText,
                fontSize = 14.sp,
                fontWeight = FontWeight.Medium,
                textAlign = TextAlign.Center,
                style = if (isPressed) {
                    androidx.compose.ui.text.TextStyle(
                        shadow = androidx.compose.ui.graphics.Shadow(
                            color = accentColor,
                            blurRadius = 12f,
                        ),
                    )
                } else {
                    androidx.compose.ui.text.TextStyle.Default
                },
            )
            Text(
                text = "小",
                color = (if (isPressed) accentColor else keyText).copy(alpha = 0.6f),
                fontSize = 9.sp,
                textAlign = TextAlign.Center,
            )
        }

        // Popup
        if (showPopup) {
            val popupOffsetPx = with(LocalDensity.current) { -56.dp.roundToPx() }
            Popup(
                alignment = Alignment.TopCenter,
                offset = IntOffset(0, popupOffsetPx),
                properties = PopupProperties(focusable = false),
            ) {
                Box(
                    modifier = Modifier
                        .size(48.dp, 48.dp)
                        .clip(RoundedCornerShape(8.dp))
                        .background(accentColor.copy(alpha = 0.9f)),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = popupLabel,
                        color = Color(0xFF000000),
                        fontSize = 22.sp,
                        fontWeight = FontWeight.Bold,
                    )
                }
            }
        }
    }
}

// ─────────────────────────────────────────────────────────────────
// Modifier key (backspace with long-press repeat)
// ─────────────────────────────────────────────────────────────────

@Composable
private fun FlickModKeyView(
    label: String,
    service: NacreInputMethodService,
    modifier: Modifier = Modifier,
    row: Int = 0,
    column: Int = 0,
) {
    var isPressed by remember { mutableStateOf(false) }
    var bsRepeating by remember { mutableStateOf(false) }

    // Long-press repeat backspace
    LaunchedEffect(bsRepeating) {
        if (!bsRepeating) return@LaunchedEffect
        delay(80L)
        while (bsRepeating) {
            service.inputEngine.processFlickBackspace()
            service.feedbackManager.onKeyPress(KeyAction.Backspace)
            delay(50L)
        }
    }

    val scale by animateFloatAsState(
        targetValue = if (isPressed) 0.95f else 1f,
        animationSpec = tween(durationMillis = 30),
        label = "modKeyScale",
    )

    val theme = service.currentTheme
    val keyBg = Color(theme.keyBackground.toInt())
    val keyBgPressed = Color(theme.keyBackgroundPressed.toInt())
    val keyText = Color(theme.keyText.toInt())
    val accentColor = Color(theme.accent.toInt())
    val surfaceColor = Color(theme.surface.toInt())

    val lighting = service.keyLighting
    val lightingColor = lighting.getKeyColor(label, row, column)

    val glowAlpha by animateFloatAsState(
        targetValue = if (isPressed) 0.8f else 0f,
        animationSpec = tween(durationMillis = if (isPressed) 30 else 200),
        label = "modKeyGlow",
    )
    val pressGlow = if (isPressed) accentColor.copy(alpha = glowAlpha) else Color.Transparent
    val borderColor = when {
        pressGlow != Color.Transparent -> pressGlow
        lightingColor != Color.Transparent -> lightingColor
        else -> Color.Transparent
    }

    val shape = RoundedCornerShape(6.dp)

    Box(
        modifier = modifier
            .padding(horizontal = 1.dp, vertical = 1.dp)
            .scale(scale)
            .clip(shape)
            .background(if (isPressed) keyBgPressed else surfaceColor)
            .padding(bottom = 1.5.dp)
            .clip(shape)
            .background(if (isPressed) keyBgPressed else keyBg)
            .then(
                if (borderColor != Color.Transparent) {
                    Modifier.border(width = 1.dp, color = borderColor, shape = shape)
                } else {
                    Modifier.border(
                        width = 0.5.dp,
                        color = surfaceColor.copy(alpha = 0.5f),
                        shape = shape,
                    )
                }
            )
            .pointerInput(label) {
                awaitEachGesture {
                    val downTime = System.currentTimeMillis()
                    awaitFirstDown(requireUnconsumed = false)
                    isPressed = true
                    var longPressHandled = false

                    try {
                        while (true) {
                            val elapsed = System.currentTimeMillis() - downTime
                            val timeout = maxOf(1L, 350L - elapsed)
                            val event = withTimeoutOrNull(timeout) {
                                awaitPointerEvent()
                            }

                            if (event == null) {
                                // Long press: start repeat
                                if (!longPressHandled) {
                                    longPressHandled = true
                                    bsRepeating = true
                                    service.feedbackManager.onLongPress()
                                }
                                continue
                            }

                            val change = event.changes.firstOrNull() ?: break
                            if (change.pressed) {
                                change.consume()
                            } else {
                                change.consume()
                                break
                            }
                        }
                    } finally {
                        isPressed = false
                        bsRepeating = false
                    }

                    if (!longPressHandled) {
                        service.inputEngine.processFlickBackspace()
                        service.feedbackManager.onKeyPress(KeyAction.Backspace)
                        lighting.onKeyPress(label, column)
                    }
                }
            },
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = label,
            color = if (isPressed) accentColor else keyText,
            fontSize = 16.sp,
            fontWeight = FontWeight.Medium,
            textAlign = TextAlign.Center,
            style = if (isPressed) {
                androidx.compose.ui.text.TextStyle(
                    shadow = androidx.compose.ui.graphics.Shadow(
                        color = accentColor,
                        blurRadius = 12f,
                    ),
                )
            } else {
                androidx.compose.ui.text.TextStyle.Default
            },
        )
    }
}

// ─────────────────────────────────────────────────────────────────
// Bottom row
// ─────────────────────────────────────────────────────────────────

/**
 * Bottom row: [↑(0.5x)] [↓(0.5x)] | [変換] | [Alt]
 * Enter has moved into the kana grid (row 4, right side key).
 * Space is the ␣ side key in row 3 of the grid.
 */
@Composable
private fun FlickBottomRow(service: NacreInputMethodService) {
    val keys = listOf(
        KeyDef(
            "↑",
            action = KeyAction.KeyCode(android.view.KeyEvent.KEYCODE_DPAD_UP),
            widthMultiplier = 0.5f,
        ),
        KeyDef(
            "↓",
            action = KeyAction.KeyCode(android.view.KeyEvent.KEYCODE_DPAD_DOWN),
            widthMultiplier = 0.5f,
        ),
        KeyDef("変換", action = KeyAction.Henkan),
        KeyDef("Alt", action = KeyAction.Alt),
    )
    KeyRow(keys = keys, service = service, rowIndex = 4, keyHeightDp = 28f)
}
