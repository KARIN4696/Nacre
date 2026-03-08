package space.manus.nacre.ime.keyboard

import android.content.Intent
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.launch
import space.manus.nacre.ime.NacreInputMethodService
import space.manus.nacre.ime.input.TextTransformer

private val PaletteBackground = Color(0xFF0F0F23)
private val SearchFieldBg = Color(0xFF16213E)
private val ItemBg = Color(0xFF2A2A4A)
private val ItemText = Color(0xFFE0E0E0)
private val ItemSubtext = Color(0xFF6666AA)
private val AccentColor = Color(0xFF00D4AA)
private val SearchText = Color(0xFFE0E0E0)
private val PlaceholderText = Color(0xFF555577)

enum class CommandType {
    BuiltIn,
    Macro,
    Snippet,
}

data class CommandItem(
    val name: String,
    val trigger: String,
    val type: CommandType,
    val action: () -> Unit,
)

@Composable
fun CommandPalette(
    service: NacreInputMethodService,
    onDismiss: () -> Unit,
) {
    var query by remember { mutableStateOf("") }
    val focusRequester = remember { FocusRequester() }

    // Show clipboard panel inside command palette
    var showClipboard by remember { mutableStateOf(false) }

    if (showClipboard) {
        ClipboardPanel(service = service, onDismiss = { showClipboard = false })
        return
    }

    val commands = remember(service) {
        buildCommandList(service, onDismiss = onDismiss, onShowClipboard = { showClipboard = true })
    }

    val filtered = remember(query, commands) {
        if (query.isBlank()) {
            commands
        } else {
            val q = query.lowercase()
            commands.filter {
                it.name.lowercase().contains(q) || it.trigger.lowercase().contains(q)
            }
        }
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(max = 260.dp)
            .background(PaletteBackground)
            .padding(horizontal = 8.dp, vertical = 4.dp),
    ) {
        // Search field
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 4.dp)
                .clip(RoundedCornerShape(8.dp))
                .background(SearchFieldBg)
                .padding(horizontal = 12.dp, vertical = 8.dp),
        ) {
            if (query.isEmpty()) {
                Text(
                    text = "Type a command...",
                    color = PlaceholderText,
                    fontSize = 14.sp,
                )
            }
            BasicTextField(
                value = query,
                onValueChange = { query = it },
                textStyle = TextStyle(color = SearchText, fontSize = 14.sp),
                singleLine = true,
                cursorBrush = SolidColor(AccentColor),
                modifier = Modifier
                    .fillMaxWidth()
                    .focusRequester(focusRequester),
            )
        }

        // Results
        if (filtered.isEmpty()) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(48.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = "No matching commands",
                    color = ItemSubtext,
                    fontSize = 13.sp,
                )
            }
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(3.dp),
            ) {
                items(filtered, key = { it.name }) { item ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(6.dp))
                            .background(ItemBg)
                            .clickable { item.action() }
                            .padding(horizontal = 10.dp, vertical = 8.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = item.name,
                                color = ItemText,
                                fontSize = 13.sp,
                            )
                            if (item.trigger.isNotBlank()) {
                                Text(
                                    text = item.trigger,
                                    color = ItemSubtext,
                                    fontSize = 11.sp,
                                )
                            }
                        }
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = when (item.type) {
                                CommandType.BuiltIn -> "\u2318"
                                CommandType.Macro -> "\u26A1"
                                CommandType.Snippet -> "\u2702"
                            },
                            color = AccentColor,
                            fontSize = 14.sp,
                        )
                    }
                }
            }
        }
    }

    LaunchedEffect(Unit) {
        focusRequester.requestFocus()
    }
}

private fun buildCommandList(
    service: NacreInputMethodService,
    onDismiss: () -> Unit,
    onShowClipboard: () -> Unit,
): List<CommandItem> {
    val items = mutableListOf<CommandItem>()

    // Built-in commands
    items.add(
        CommandItem(
            name = "Clipboard History",
            trigger = "clipboard",
            type = CommandType.BuiltIn,
            action = { onShowClipboard() },
        )
    )

    items.add(
        CommandItem(
            name = "Settings",
            trigger = "settings",
            type = CommandType.BuiltIn,
            action = {
                try {
                    val intent = Intent("android.settings.INPUT_METHOD_SETTINGS").apply {
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    }
                    service.startActivity(intent)
                } catch (_: Exception) {
                    // Settings activity not available
                }
                onDismiss()
            },
        )
    )

    items.add(
        CommandItem(
            name = "Switch IME",
            trigger = "ime",
            type = CommandType.BuiltIn,
            action = {
                @Suppress("DEPRECATION")
                service.switchToNextInputMethod(false)
                onDismiss()
            },
        )
    )

    items.add(
        CommandItem(
            name = "Toggle Japanese",
            trigger = "ja",
            type = CommandType.BuiltIn,
            action = {
                service.layerManager.toggleJapanese()
                onDismiss()
            },
        )
    )

    // Voice input
    items.add(
        CommandItem(
            name = "Voice Input",
            trigger = "voice mic",
            type = CommandType.BuiltIn,
            action = {
                val lang = if (service.layerManager.isJapanese) "ja-JP" else "en-US"
                service.voiceInputManager.startListening(lang)
                onDismiss()
            },
        )
    )

    // Text transformation commands
    for (cmd in TextTransformer.commands) {
        items.add(
            CommandItem(
                name = cmd.label,
                trigger = "${cmd.id} ${cmd.description}",
                type = CommandType.BuiltIn,
                action = {
                    val ic = service.currentInputConnection
                    if (ic != null) {
                        val extracted = ic.getExtractedText(
                            android.view.inputmethod.ExtractedTextRequest(), 0,
                        )
                        val selected = extracted?.text?.let { full ->
                            val start = extracted.selectionStart
                            val end = extracted.selectionEnd
                            if (start >= 0 && end > start && end <= full.length) {
                                full.substring(start, end)
                            } else {
                                null
                            }
                        }
                        if (selected != null && selected.isNotEmpty()) {
                            val result = TextTransformer.transform(selected, cmd.id)
                            ic.commitText(result, 1)
                        }
                    }
                    onDismiss()
                },
            )
        )
    }

    // Dynamic macros from MacroEngine
    for (macro in service.macroEngine.macros) {
        items.add(
            CommandItem(
                name = macro.name,
                trigger = macro.trigger ?: "",
                type = CommandType.Macro,
                action = {
                    val ic = service.currentInputConnection
                    if (ic != null) {
                        MainScope().launch {
                            val freshIc = service.currentInputConnection ?: return@launch
                            service.macroEngine.executeMacro(macro, freshIc)
                        }
                    }
                    onDismiss()
                },
            )
        )
    }

    // Dynamic snippets from SnippetEngine
    for (snippet in service.snippetEngine.snippets) {
        items.add(
            CommandItem(
                name = snippet.name,
                trigger = snippet.trigger ?: "",
                type = CommandType.Snippet,
                action = {
                    val ic = service.currentInputConnection
                    if (ic != null) {
                        service.snippetEngine.insertSnippet(snippet, ic)
                    }
                    onDismiss()
                },
            )
        )
    }

    return items
}
