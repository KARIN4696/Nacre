package space.manus.nacre.ime.keyboard

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import space.manus.nacre.ime.NacreInputMethodService
import space.manus.nacre.ime.input.MacroEngine
import space.manus.nacre.ime.trackball.TrackballView
import kotlinx.coroutines.launch

private val PadBackground = Color(0xFF0A0A1A)
private val MacroButtonBg = Color(0xFF1E1E3A)
private val MacroButtonText = Color(0xFFE0E0E0)
private val VoiceButtonBg = Color(0xFF00D4AA)
private val VoiceButtonText = Color(0xFF0A0A1A)

/**
 * Quick input pad for small sub-displays (e.g. Galaxy Z Flip cover screen).
 *
 * Layout:
 *  - 3x2 grid of customizable macro buttons (minimum 60dp touch targets)
 *  - Large voice input button in the center area
 *  - Mini trackball (40dp) at the bottom
 */
@Composable
fun QuickInputPad(service: NacreInputMethodService) {
    val macroEngine = service.macroEngine
    val macros = macroEngine.macros
    val coroutineScope = rememberCoroutineScope()
    val haptic = LocalHapticFeedback.current

    // Take up to 6 macros for the grid
    val gridMacros = macros.take(6)

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(PadBackground)
            .padding(8.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        // 3x2 macro button grid
        for (rowIndex in 0 until 2) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                for (colIndex in 0 until 3) {
                    val macroIndex = rowIndex * 3 + colIndex
                    val macro = gridMacros.getOrNull(macroIndex)

                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .height(60.dp)
                            .clip(RoundedCornerShape(12.dp))
                            .background(MacroButtonBg)
                            .then(
                                if (macro != null) {
                                    Modifier
                                        .clickable {
                                            haptic.performHapticFeedback(
                                                HapticFeedbackType.TextHandleMove,
                                            )
                                            val ic = service.currentInputConnection
                                            if (ic != null) {
                                                coroutineScope.launch {
                                                    macroEngine.executeMacro(macro, ic)
                                                }
                                            }
                                        }
                                        .semantics {
                                            contentDescription = "Macro: ${macro.name}"
                                        }
                                } else {
                                    Modifier.semantics {
                                        contentDescription = "Empty macro slot"
                                    }
                                },
                            ),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            text = macro?.name ?: "",
                            color = MacroButtonText,
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Medium,
                            textAlign = TextAlign.Center,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.padding(horizontal = 4.dp),
                        )
                    }
                }
            }
        }

        Spacer(modifier = Modifier.height(8.dp))

        // Voice input button
        Box(
            modifier = Modifier
                .size(72.dp)
                .clip(CircleShape)
                .background(VoiceButtonBg)
                .clickable {
                    haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                    // Trigger voice input via the system
                    val ic = service.currentInputConnection
                    ic?.performEditorAction(android.view.inputmethod.EditorInfo.IME_ACTION_NONE)
                }
                .semantics {
                    contentDescription = "Voice input"
                },
            contentAlignment = Alignment.Center,
        ) {
            // Microphone icon as text (Unicode)
            Text(
                text = "\uD83C\uDFA4",
                fontSize = 28.sp,
                color = VoiceButtonText,
            )
        }

        Spacer(modifier = Modifier.height(8.dp))

        // Mini trackball
        TrackballView(
            service = service,
            modifier = Modifier.size(40.dp),
        )
    }
}
