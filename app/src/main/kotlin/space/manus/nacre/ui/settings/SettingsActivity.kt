package space.manus.nacre.ui.settings

import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import android.view.inputmethod.InputMethodManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.io.File

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

    // Read crash log from internal storage
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

        Spacer(modifier = Modifier.height(48.dp))

        // Step 1: Enable IME
        SettingsCard(
            title = "1. Enable Nacre",
            description = "Open system settings to enable Nacre as an input method",
            onClick = {
                context.startActivity(Intent(Settings.ACTION_INPUT_METHOD_SETTINGS))
            },
        )

        Spacer(modifier = Modifier.height(16.dp))

        // Step 2: Select IME
        SettingsCard(
            title = "2. Select Nacre",
            description = "Switch to Nacre as your active keyboard",
            onClick = {
                val imm = context.getSystemService(InputMethodManager::class.java)
                imm.showInputMethodPicker()
            },
        )

        Spacer(modifier = Modifier.height(32.dp))

        Text(
            text = "No internet permission. Your keystrokes stay on device.",
            fontSize = 12.sp,
            color = NacreTextDim,
        )

        // Crash log section
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
