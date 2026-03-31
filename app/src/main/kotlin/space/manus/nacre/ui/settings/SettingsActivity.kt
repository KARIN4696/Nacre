package space.manus.nacre.ui.settings

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.view.inputmethod.InputMethodManager
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import space.manus.nacre.ai.KenLmJni
import space.manus.nacre.config.ConfigRepository
import space.manus.nacre.config.PresetProvider
import space.manus.nacre.config.ThemeProvider
import space.manus.nacre.ime.feedback.HapticManager
import space.manus.nacre.ime.feedback.SoundManager
import space.manus.nacre.ime.keyboard.KeyLighting
import java.io.File
import kotlin.math.roundToInt

private val NacreBackground = Color(0xFF1A1A2E)
private val NacreSurface = Color(0xFF16213E)
private val NacreAccent = Color(0xFF00D4AA)
private val NacreText = Color(0xFFE0E0E0)
private val NacreTextDim = Color(0xFF8888AA)

class SettingsActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            NacreSettingsScreen()
        }
    }
}

@Composable
fun NacreSettingsScreen() {
    val context = LocalContext.current
    val config = remember { ConfigRepository(context) }

    // Read crash log
    val crashLog = remember {
        try {
            val logDir = File(context.filesDir, "crash-logs")
            logDir.listFiles()
                ?.sortedByDescending { it.lastModified() }
                ?.firstOrNull()
                ?.readText()
        } catch (_: Exception) { null }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(NacreBackground)
            .verticalScroll(rememberScrollState())
            .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Spacer(modifier = Modifier.height(48.dp))

        Text(
            text = "Nacre",
            fontSize = 36.sp,
            fontWeight = FontWeight.Bold,
            color = NacreAccent,
        )

        Text(
            text = "Developer Keyboard",
            fontSize = 14.sp,
            color = NacreTextDim,
        )

        Spacer(modifier = Modifier.height(32.dp))

        // --- Setup ---
        SectionHeader("Setup")

        SettingsCard(
            title = "1. Enable Nacre",
            description = "Open system settings to enable Nacre as an input method",
            onClick = {
                context.startActivity(Intent(Settings.ACTION_INPUT_METHOD_SETTINGS))
            },
        )

        Spacer(modifier = Modifier.height(8.dp))

        SettingsCard(
            title = "2. Select Nacre",
            description = "Switch to Nacre as your active keyboard",
            onClick = {
                val imm = context.getSystemService(InputMethodManager::class.java)
                imm.showInputMethodPicker()
            },
        )

        Spacer(modifier = Modifier.height(24.dp))

        // --- Sound ---
        SectionHeader("Sound")
        SoundSection(config, context)

        Spacer(modifier = Modifier.height(24.dp))

        // --- Haptics ---
        SectionHeader("Haptics")
        HapticSection(config, context)

        Spacer(modifier = Modifier.height(24.dp))

        // --- Lighting ---
        SectionHeader("Key Lighting")
        LightingSection(config, context)

        Spacer(modifier = Modifier.height(24.dp))

        // --- Layout ---
        SectionHeader("Layout")
        LayoutSection(config)

        Spacer(modifier = Modifier.height(24.dp))

        // --- Preset ---
        SectionHeader("Key Preset")
        PresetSection(config)

        Spacer(modifier = Modifier.height(24.dp))

        // --- Theme ---
        SectionHeader("Theme")
        ThemeSection(config, context)

        Spacer(modifier = Modifier.height(24.dp))

        // --- Auto Convert ---
        SectionHeader("Auto Convert")
        AutoConvertSection(config)

        Spacer(modifier = Modifier.height(24.dp))

        // --- AI Models ---
        SectionHeader("AI Models")
        KenLmModelSection()
        Spacer(modifier = Modifier.height(8.dp))
        WhisperModelSection()

        Spacer(modifier = Modifier.height(24.dp))

        // --- Reset ---
        var showResetDialog by remember { mutableStateOf(false) }
        Button(
            onClick = { showResetDialog = true },
            colors = ButtonDefaults.buttonColors(
                containerColor = Color(0xFF442222),
                contentColor = Color(0xFFFF6B6B),
            ),
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text("Reset All Settings")
        }

        if (showResetDialog) {
            AlertDialog(
                onDismissRequest = { showResetDialog = false },
                title = { Text("Reset Settings?") },
                text = { Text("All settings will be restored to defaults.") },
                confirmButton = {
                    TextButton(onClick = {
                        config.resetToDefaults()
                        showResetDialog = false
                    }) {
                        Text("Reset", color = Color(0xFFFF6B6B))
                    }
                },
                dismissButton = {
                    TextButton(onClick = { showResetDialog = false }) {
                        Text("Cancel")
                    }
                },
            )
        }

        Spacer(modifier = Modifier.height(24.dp))

        Text(
            text = "No internet permission. Your keystrokes stay on device.",
            fontSize = 12.sp,
            color = NacreTextDim,
        )

        // Crash log
        if (crashLog != null) {
            Spacer(modifier = Modifier.height(32.dp))
            Text(
                text = "Last Crash Log",
                fontSize = 16.sp,
                fontWeight = FontWeight.SemiBold,
                color = Color(0xFFFF6B6B),
            )
            Spacer(modifier = Modifier.height(8.dp))
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(8.dp),
                colors = CardDefaults.cardColors(containerColor = Color(0xFF0A0A1A)),
            ) {
                Text(
                    text = crashLog,
                    fontSize = 9.sp,
                    fontFamily = FontFamily.Monospace,
                    color = NacreText,
                    modifier = Modifier.padding(12.dp),
                )
            }
        }

        Spacer(modifier = Modifier.height(48.dp))
    }
}

// --- Section Components ---

@Composable
private fun SectionHeader(text: String) {
    Text(
        text = text,
        fontSize = 13.sp,
        fontWeight = FontWeight.Bold,
        color = NacreAccent,
        modifier = Modifier
            .fillMaxWidth()
            .padding(bottom = 8.dp),
    )
}

@Composable
private fun SoundSection(config: ConfigRepository, context: Context) {
    val soundPrefs = context.getSharedPreferences("nacre_sound", Context.MODE_PRIVATE)
    var selectedProfile by remember {
        mutableStateOf(
            soundPrefs.getString("profile", SoundManager.Profile.THOCK.name)
                ?: SoundManager.Profile.THOCK.name,
        )
    }
    var volume by remember {
        mutableStateOf(soundPrefs.getInt("volume", 70).toFloat())
    }

    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = NacreSurface),
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text("Sound Profile", color = NacreText, fontSize = 14.sp)
            Spacer(modifier = Modifier.height(8.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                for (profile in SoundManager.Profile.entries) {
                    val isSelected = selectedProfile == profile.name
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .clip(RoundedCornerShape(8.dp))
                            .background(if (isSelected) NacreAccent.copy(alpha = 0.2f) else Color.Transparent)
                            .then(
                                if (isSelected) Modifier.border(1.dp, NacreAccent, RoundedCornerShape(8.dp))
                                else Modifier,
                            )
                            .clickable {
                                selectedProfile = profile.name
                                soundPrefs
                                    .edit()
                                    .putString("profile", profile.name)
                                    .apply()
                            }
                            .padding(vertical = 8.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            text = profile.displayName,
                            color = if (isSelected) NacreAccent else NacreTextDim,
                            fontSize = 12.sp,
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(16.dp))
            Text("Volume: ${volume.roundToInt()}%", color = NacreText, fontSize = 14.sp)
            Slider(
                value = volume,
                onValueChange = { volume = it },
                onValueChangeFinished = {
                    soundPrefs.edit().putInt("volume", volume.roundToInt()).apply()
                },
                valueRange = 0f..100f,
                colors = SliderDefaults.colors(
                    thumbColor = NacreAccent,
                    activeTrackColor = NacreAccent,
                    inactiveTrackColor = NacreTextDim.copy(alpha = 0.3f),
                ),
            )
        }
    }
}

@Composable
private fun HapticSection(config: ConfigRepository, context: Context) {
    val hapticPrefs = context.getSharedPreferences("nacre_haptic", Context.MODE_PRIVATE)
    var selectedStrength by remember {
        mutableStateOf(
            hapticPrefs.getString("strength", HapticManager.Strength.MEDIUM.name)
                ?: HapticManager.Strength.MEDIUM.name,
        )
    }

    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = NacreSurface),
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text("Haptic Strength", color = NacreText, fontSize = 14.sp)
            Spacer(modifier = Modifier.height(8.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                for (strength in HapticManager.Strength.entries) {
                    val isSelected = selectedStrength == strength.name
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .clip(RoundedCornerShape(8.dp))
                            .background(if (isSelected) NacreAccent.copy(alpha = 0.2f) else Color.Transparent)
                            .then(
                                if (isSelected) Modifier.border(1.dp, NacreAccent, RoundedCornerShape(8.dp))
                                else Modifier,
                            )
                            .clickable {
                                selectedStrength = strength.name
                                hapticPrefs
                                    .edit()
                                    .putString("strength", strength.name)
                                    .apply()
                            }
                            .padding(vertical = 8.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            text = strength.name.lowercase()
                                .replaceFirstChar { it.uppercase() },
                            color = if (isSelected) NacreAccent else NacreTextDim,
                            fontSize = 12.sp,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun LightingSection(config: ConfigRepository, context: Context) {
    val lightPrefs = context.getSharedPreferences("nacre_lighting", Context.MODE_PRIVATE)
    var selectedMode by remember {
        mutableStateOf(
            lightPrefs.getString("mode", KeyLighting.Mode.OFF.name)
                ?: KeyLighting.Mode.OFF.name,
        )
    }
    var hue by remember {
        mutableStateOf(lightPrefs.getFloat("hue", 170f))
    }

    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = NacreSurface),
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text("Lighting Mode", color = NacreText, fontSize = 14.sp)
            Spacer(modifier = Modifier.height(8.dp))

            // Two rows for 7 modes
            for (row in KeyLighting.Mode.entries.chunked(4)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    for (mode in row) {
                        val isSelected = selectedMode == mode.name
                        Box(
                            modifier = Modifier
                                .weight(1f)
                                .clip(RoundedCornerShape(8.dp))
                                .background(if (isSelected) NacreAccent.copy(alpha = 0.2f) else Color.Transparent)
                                .then(
                                    if (isSelected) Modifier.border(1.dp, NacreAccent, RoundedCornerShape(8.dp))
                                    else Modifier,
                                )
                                .clickable {
                                    selectedMode = mode.name
                                    lightPrefs
                                        .edit()
                                        .putString("mode", mode.name)
                                        .apply()
                                }
                                .padding(vertical = 6.dp),
                            contentAlignment = Alignment.Center,
                        ) {
                            Text(
                                text = mode.displayName,
                                color = if (isSelected) NacreAccent else NacreTextDim,
                                fontSize = 11.sp,
                            )
                        }
                    }
                    // Fill remaining space if row has fewer items
                    repeat(4 - row.size) {
                        Spacer(modifier = Modifier.weight(1f))
                    }
                }
                Spacer(modifier = Modifier.height(4.dp))
            }

            if (selectedMode != KeyLighting.Mode.OFF.name) {
                Spacer(modifier = Modifier.height(12.dp))
                Text("Base Hue: ${hue.roundToInt()}\u00B0", color = NacreText, fontSize = 14.sp)
                Slider(
                    value = hue,
                    onValueChange = { hue = it },
                    onValueChangeFinished = {
                        lightPrefs.edit().putFloat("hue", hue).apply()
                    },
                    valueRange = 0f..360f,
                    colors = SliderDefaults.colors(
                        thumbColor = NacreAccent,
                        activeTrackColor = NacreAccent,
                        inactiveTrackColor = NacreTextDim.copy(alpha = 0.3f),
                    ),
                )
            }
        }
    }
}

@Composable
private fun LayoutSection(config: ConfigRepository) {
    var vAngle by remember { mutableStateOf(config.vAngle) }
    var keyboardHeight by remember { mutableStateOf(config.keyboardHeight.toFloat()) }

    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = NacreSurface),
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                "V-Split Angle: ${vAngle.roundToInt()}\u00B0",
                color = NacreText,
                fontSize = 14.sp,
            )
            Slider(
                value = vAngle,
                onValueChange = { vAngle = it },
                onValueChangeFinished = { config.vAngle = vAngle },
                valueRange = 0f..30f,
                colors = SliderDefaults.colors(
                    thumbColor = NacreAccent,
                    activeTrackColor = NacreAccent,
                    inactiveTrackColor = NacreTextDim.copy(alpha = 0.3f),
                ),
            )

            Spacer(modifier = Modifier.height(12.dp))

            Text(
                "Keyboard Height: ${keyboardHeight.roundToInt()}dp",
                color = NacreText,
                fontSize = 14.sp,
            )
            Slider(
                value = keyboardHeight,
                onValueChange = { keyboardHeight = it },
                onValueChangeFinished = { config.keyboardHeight = keyboardHeight.roundToInt() },
                valueRange = 180f..400f,
                colors = SliderDefaults.colors(
                    thumbColor = NacreAccent,
                    activeTrackColor = NacreAccent,
                    inactiveTrackColor = NacreTextDim.copy(alpha = 0.3f),
                ),
            )
        }
    }
}

@Composable
private fun PresetSection(config: ConfigRepository) {
    var selectedPreset by remember { mutableStateOf(config.selectedPreset) }

    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = NacreSurface),
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            for (row in PresetProvider.PresetType.entries.chunked(3)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    for (preset in row) {
                        val isSelected = selectedPreset == preset.name
                        Box(
                            modifier = Modifier
                                .weight(1f)
                                .clip(RoundedCornerShape(8.dp))
                                .background(if (isSelected) NacreAccent.copy(alpha = 0.2f) else Color.Transparent)
                                .then(
                                    if (isSelected) Modifier.border(1.dp, NacreAccent, RoundedCornerShape(8.dp))
                                    else Modifier,
                                )
                                .clickable {
                                    selectedPreset = preset.name
                                    config.selectedPreset = preset.name
                                }
                                .padding(vertical = 10.dp),
                            contentAlignment = Alignment.Center,
                        ) {
                            Text(
                                text = preset.name,
                                color = if (isSelected) NacreAccent else NacreTextDim,
                                fontSize = 13.sp,
                            )
                        }
                    }
                    repeat(3 - row.size) {
                        Spacer(modifier = Modifier.weight(1f))
                    }
                }
                Spacer(modifier = Modifier.height(4.dp))
            }
        }
    }
}

@Composable
private fun ThemeSection(config: ConfigRepository, context: Context) {
    var selectedTheme by remember { mutableStateOf(config.selectedTheme) }

    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = NacreSurface),
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                for (theme in ThemeProvider.themes) {
                    val isSelected = selectedTheme.equals(theme.name, ignoreCase = true)
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .clip(RoundedCornerShape(8.dp))
                            .background(Color(theme.background.toInt()))
                            .then(
                                if (isSelected) Modifier.border(2.dp, NacreAccent, RoundedCornerShape(8.dp))
                                else Modifier.border(1.dp, NacreTextDim.copy(alpha = 0.3f), RoundedCornerShape(8.dp)),
                            )
                            .clickable {
                                selectedTheme = theme.name
                                config.selectedTheme = theme.name
                                ThemeProvider.saveSelectedTheme(context, theme.name)
                            }
                            .padding(vertical = 16.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            text = theme.name,
                            color = Color(theme.keyText.toInt()),
                            fontSize = 13.sp,
                            fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun AutoConvertSection(config: ConfigRepository) {
    val context = LocalContext.current
    val acPrefs = context.getSharedPreferences("nacre_auto_convert", Context.MODE_PRIVATE)
    var enabled by remember { mutableStateOf(acPrefs.getBoolean("auto_convert_enabled", true)) }

    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = NacreSurface),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text("Auto Convert", color = NacreText, fontSize = 14.sp)
                Text(
                    "-> to \u2192, != to \u2260, etc.",
                    color = NacreTextDim,
                    fontSize = 12.sp,
                )
            }
            Switch(
                checked = enabled,
                onCheckedChange = {
                    enabled = it
                    acPrefs.edit().putBoolean("auto_convert_enabled", it).apply()
                    config.autoConvertEnabled = it
                },
                colors = SwitchDefaults.colors(
                    checkedThumbColor = NacreAccent,
                    checkedTrackColor = NacreAccent.copy(alpha = 0.3f),
                ),
            )
        }
    }
}

@Composable
private fun KenLmModelSection() {
    val context = LocalContext.current
    val modelsDir = remember { File(context.filesDir, "models") }
    val modelFile = remember { File(modelsDir, "japanese-5gram.klm") }
    var modelExists by remember { mutableStateOf(modelFile.exists()) }
    var modelSize by remember {
        mutableStateOf(if (modelFile.exists()) modelFile.length() / 1024 / 1024 else 0L)
    }
    var importing by remember { mutableStateOf(false) }

    val launcher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri: Uri? ->
        if (uri == null) return@rememberLauncherForActivityResult
        importing = true
        Thread {
            try {
                modelsDir.mkdirs()
                val tmpFile = File(modelsDir, "japanese-5gram.klm.tmp")
                context.contentResolver.openInputStream(uri)?.use { input ->
                    tmpFile.outputStream().use { output ->
                        input.copyTo(output, bufferSize = 65536)
                    }
                }
                tmpFile.renameTo(modelFile)
                val sizeMb = modelFile.length() / 1024 / 1024
                android.os.Handler(android.os.Looper.getMainLooper()).post {
                    modelExists = true
                    modelSize = sizeMb
                    importing = false
                    Toast.makeText(context, "KenLM model imported (${sizeMb}MB). Restart keyboard to activate.", Toast.LENGTH_LONG).show()
                }
            } catch (e: Exception) {
                android.os.Handler(android.os.Looper.getMainLooper()).post {
                    importing = false
                    Toast.makeText(context, "Import failed: ${e.message}", Toast.LENGTH_LONG).show()
                }
            }
        }.start()
    }

    // Also check if model is found anywhere on device via ModelDownloader
    val downloader = remember { space.manus.nacre.ai.ModelDownloader(context) }
    val foundPath = remember { downloader.getKenLmModelPath() }
    val effectiveExists = modelExists || foundPath != null
    val effectiveSize = if (modelExists) modelSize else {
        foundPath?.let { java.io.File(it).length() / 1024 / 1024 } ?: 0L
    }

    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = NacreSurface),
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("KenLM 5-gram", color = NacreText, fontSize = 14.sp, fontWeight = FontWeight.Medium)
                Spacer(modifier = Modifier.width(8.dp))
                if (effectiveExists) {
                    Text("Ready", color = Color(0xFF4CAF50), fontSize = 12.sp, fontWeight = FontWeight.Bold)
                } else {
                    Text("Not found", color = Color(0xFFFF6666), fontSize = 12.sp, fontWeight = FontWeight.Bold)
                }
            }
            Spacer(modifier = Modifier.height(4.dp))
            if (effectiveExists) {
                Text("Japanese text conversion (${effectiveSize}MB)", color = NacreTextDim, fontSize = 12.sp)
                val displayPath = if (modelExists) modelFile.absolutePath else foundPath ?: ""
                Spacer(modifier = Modifier.height(2.dp))
                Text(displayPath, color = NacreTextDim.copy(alpha = 0.5f), fontSize = 10.sp, maxLines = 1)
            } else {
                Text("Import a .klm model file for better Japanese conversion", color = NacreTextDim, fontSize = 12.sp)
            }
            Spacer(modifier = Modifier.height(12.dp))
            Button(
                onClick = { launcher.launch(arrayOf("*/*")) },
                enabled = !importing,
                colors = ButtonDefaults.buttonColors(
                    containerColor = NacreAccent,
                    contentColor = Color.Black,
                ),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(
                    if (importing) "Importing..."
                    else if (modelExists) "Replace Model"
                    else "Import Model (.klm)"
                )
            }
        }
    }
}

@Composable
private fun WhisperModelSection() {
    val context = LocalContext.current
    val downloader = remember { space.manus.nacre.ai.ModelDownloader(context) }
    var modelPath by remember { mutableStateOf(downloader.getWhisperModelPath()) }
    var modelSize by remember {
        mutableStateOf(
            modelPath?.let { java.io.File(it).length() / 1024 / 1024 } ?: 0L
        )
    }
    var downloading by remember { mutableStateOf(false) }

    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = NacreSurface),
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = if (modelPath != null) "Whisper Base" else "Whisper Base",
                    color = NacreText,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Medium,
                )
                Spacer(modifier = Modifier.width(8.dp))
                if (modelPath != null) {
                    Text(
                        text = "Ready",
                        color = Color(0xFF4CAF50),
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Bold,
                    )
                } else {
                    Text(
                        text = "Not found",
                        color = Color(0xFFFF6666),
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Bold,
                    )
                }
            }

            Spacer(modifier = Modifier.height(4.dp))

            if (modelPath != null) {
                Text(
                    text = "Offline voice input (${modelSize}MB)",
                    color = NacreTextDim,
                    fontSize = 12.sp,
                )
                Spacer(modifier = Modifier.height(2.dp))
                Text(
                    text = modelPath!!,
                    color = NacreTextDim.copy(alpha = 0.5f),
                    fontSize = 10.sp,
                    maxLines = 1,
                )
            } else {
                Text(
                    text = "Place ggml-base.bin anywhere on device, or download below",
                    color = NacreTextDim,
                    fontSize = 12.sp,
                )
                Spacer(modifier = Modifier.height(8.dp))
                Button(
                    onClick = {
                        downloading = true
                        downloader.downloadWhisperBase { success ->
                            android.os.Handler(android.os.Looper.getMainLooper()).post {
                                downloading = false
                                if (success) {
                                    modelPath = downloader.getWhisperModelPath()
                                    modelSize = modelPath?.let { java.io.File(it).length() / 1024 / 1024 } ?: 0L
                                }
                            }
                        }
                    },
                    enabled = !downloading,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = NacreAccent,
                        contentColor = Color.Black,
                    ),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(if (downloading) "Downloading..." else "Download (~142MB)")
                }
            }
        }
    }
}

@Composable
fun SettingsCard(
    title: String,
    description: String,
    onClick: () -> Unit,
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = NacreSurface),
    ) {
        Column(modifier = Modifier.padding(20.dp)) {
            Text(
                text = title,
                fontSize = 18.sp,
                fontWeight = FontWeight.SemiBold,
                color = NacreText,
            )
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = description,
                fontSize = 14.sp,
                color = NacreTextDim,
            )
        }
    }
}
