package space.manus.nacre.ime.keyboard

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import space.manus.nacre.ime.NacreInputMethodService
import space.manus.nacre.ime.input.ConversionCandidate

private val BarBackground = Color(0xFF16213E)
private val CandidateBg = Color(0xFF2A2A4A)
private val CandidateSelectedBg = Color(0xFF00D4AA)
private val CandidateText = Color(0xFFE0E0E0)
private val CandidateSelectedText = Color(0xFF0F0F23)

@Composable
fun CandidateBar(
    service: NacreInputMethodService,
    modifier: Modifier = Modifier,
) {
    val candidates = service.inputEngine.candidates
    val selectedIndex = service.inputEngine.selectedCandidateIndex

    // SPEC: hide candidate bar in password fields
    if (candidates.isEmpty() || service.inputEngine.isPasswordField) return

    val scrollState = rememberScrollState()

    // Reset scroll when candidates change
    LaunchedEffect(candidates.toList()) {
        scrollState.scrollTo(0)
    }

    Row(
        modifier = modifier
            .fillMaxWidth()
            .height(40.dp)
            .background(BarBackground)
            .horizontalScroll(scrollState)
            .padding(horizontal = 4.dp, vertical = 4.dp),
        horizontalArrangement = Arrangement.Start,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        candidates.forEachIndexed { index, candidate ->
            CandidateChip(
                candidate = candidate,
                isSelected = index == selectedIndex,
                onClick = {
                    if (service.inputEngine.isConverting) {
                        service.inputEngine.selectCandidate(index)
                    } else {
                        service.inputEngine.commitCandidate(index)
                    }
                },
                index = index,
            )
            if (index < candidates.size - 1) {
                Spacer(modifier = Modifier.width(4.dp))
            }
        }
    }
}

@Composable
private fun CandidateChip(
    candidate: ConversionCandidate,
    isSelected: Boolean,
    onClick: () -> Unit,
    index: Int,
) {
    val bg = if (isSelected) CandidateSelectedBg else CandidateBg
    val textColor = if (isSelected) CandidateSelectedText else CandidateText

    Box(
        modifier = Modifier
            .height(32.dp)
            .clip(RoundedCornerShape(6.dp))
            .background(bg)
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 4.dp)
            .semantics {
                contentDescription = "候補${index + 1}: ${candidate.surface}"
            },
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = candidate.surface,
            color = textColor,
            fontSize = 14.sp,
            fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal,
        )
    }
}
