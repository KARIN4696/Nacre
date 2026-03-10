package space.manus.nacre.ime.keyboard

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color
import kotlin.math.*

/**
 * Mechanical keyboard RGB lighting engine.
 * Provides per-key color computation for various lighting modes.
 */
class KeyLighting(context: Context) {

    enum class Mode(val displayName: String) {
        OFF("Off"),
        STATIC("Static"),
        REACTIVE("Reactive"),
        WAVE("Wave"),
        BREATHING("Breathing"),
        HEATMAP("Heatmap"),
        MATRIX("Matrix"),
    }

    companion object {
        private const val PREFS_NAME = "nacre_lighting"
        private const val KEY_MODE = "mode"
        private const val KEY_HUE = "hue"

        // Reactive wave speed
        private const val REACTIVE_DURATION_MS = 400L
        private const val REACTIVE_WAVE_SPEED = 0.003f // keys per ms

        // Wave animation period
        private const val WAVE_PERIOD_MS = 3000L

        // Breathing animation period
        private const val BREATHING_PERIOD_MS = 4000L
    }

    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    var mode: Mode = loadMode()
        private set

    /** Base hue (0-360) for static/wave/breathing modes */
    var baseHue: Float = loadHue()
        private set

    private val prefsListener = android.content.SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
        when (key) {
            KEY_MODE -> mode = loadMode()
            KEY_HUE -> baseHue = loadHue()
        }
    }

    init {
        prefs.registerOnSharedPreferenceChangeListener(prefsListener)
    }

    // Reactive: recently pressed keys with timestamps
    private val reactivePresses = mutableStateMapOf<String, Long>()

    // Heatmap: key press counts
    private val heatmapCounts = mutableStateMapOf<String, Int>()
    private var heatmapMax = 1

    // Matrix: random column activations
    private val matrixColumns = mutableStateMapOf<Int, Long>()

    // Animation ticker — updated by KeyboardScreen
    var animationTick by mutableStateOf(0L)

    fun setMode(newMode: Mode) {
        mode = newMode
        prefs.edit().putString(KEY_MODE, newMode.name).apply()
    }

    fun setBaseHue(hue: Float) {
        baseHue = hue.coerceIn(0f, 360f)
        prefs.edit().putFloat(KEY_HUE, baseHue).apply()
    }

    /** Call when a key is pressed */
    fun onKeyPress(keyId: String, column: Int = 0) {
        val now = System.currentTimeMillis()
        reactivePresses[keyId] = now

        // Heatmap tracking
        heatmapCounts[keyId] = (heatmapCounts[keyId] ?: 0) + 1
        heatmapMax = maxOf(heatmapMax, heatmapCounts[keyId] ?: 1)

        // Matrix: activate column
        matrixColumns[column] = now
    }

    // WASD highlight: fluorescent orange always-on
    private val wasdKeys = setOf("w", "a", "s", "d")
    private val FLUORESCENT_ORANGE = Color(0xFFFF6600)

    /**
     * Get the border/glow color for a specific key at the current time.
     * Returns Color.Transparent if no lighting should be shown.
     */
    fun getKeyColor(keyId: String, row: Int, column: Int): Color {
        // WASD always glows fluorescent orange
        if (keyId in wasdKeys) {
            return FLUORESCENT_ORANGE.copy(alpha = 0.85f)
        }

        val now = animationTick
        return when (mode) {
            Mode.OFF -> Color.Transparent
            Mode.STATIC -> hsvToColor(baseHue, 0.8f, 0.7f)
            Mode.REACTIVE -> reactiveColor(keyId, now)
            Mode.WAVE -> waveColor(column, now)
            Mode.BREATHING -> breathingColor(now)
            Mode.HEATMAP -> heatmapColor(keyId)
            Mode.MATRIX -> matrixColor(row, column, now)
        }
    }

    private fun reactiveColor(keyId: String, now: Long): Color {
        val pressTime = reactivePresses[keyId] ?: return Color.Transparent
        val elapsed = now - pressTime
        if (elapsed > REACTIVE_DURATION_MS) {
            reactivePresses.remove(keyId)
            return Color.Transparent
        }
        val progress = elapsed.toFloat() / REACTIVE_DURATION_MS
        val alpha = 1f - progress // Fade out
        val hue = baseHue + progress * 30f // Slight hue shift during fade
        return hsvToColor(hue % 360f, 0.9f, 0.9f).copy(alpha = alpha * 0.8f)
    }

    private fun waveColor(column: Int, now: Long): Color {
        val phase = (now % WAVE_PERIOD_MS).toFloat() / WAVE_PERIOD_MS
        val columnPhase = column.toFloat() / 10f
        val wave = sin(2.0 * PI * (phase - columnPhase)).toFloat()
        val brightness = (wave + 1f) / 2f * 0.6f + 0.1f
        val hue = (baseHue + column * 20f) % 360f
        return hsvToColor(hue, 0.8f, brightness).copy(alpha = brightness)
    }

    private fun breathingColor(now: Long): Color {
        val phase = (now % BREATHING_PERIOD_MS).toFloat() / BREATHING_PERIOD_MS
        val breath = (sin(2.0 * PI * phase.toDouble()).toFloat() + 1f) / 2f
        val alpha = breath * 0.5f + 0.05f
        return hsvToColor(baseHue, 0.7f, 0.8f).copy(alpha = alpha)
    }

    private fun heatmapColor(keyId: String): Color {
        val count = heatmapCounts[keyId] ?: 0
        if (count == 0) return Color(0xFF1A1AFF).copy(alpha = 0.2f) // Cold blue

        val ratio = count.toFloat() / heatmapMax
        // Blue (cold) → Cyan → Green → Yellow → Red (hot)
        val hue = (1f - ratio) * 240f // 240=blue, 0=red
        return hsvToColor(hue, 0.9f, 0.8f).copy(alpha = 0.3f + ratio * 0.5f)
    }

    private fun matrixColor(row: Int, column: Int, now: Long): Color {
        val activationTime = matrixColumns[column] ?: return Color.Transparent
        val elapsed = now - activationTime
        // Matrix drip: each row lights up with a delay
        val rowDelay = row * 150L
        val rowElapsed = elapsed - rowDelay
        if (rowElapsed < 0 || rowElapsed > 600L) return Color.Transparent

        val alpha = (1f - rowElapsed.toFloat() / 600f) * 0.7f
        return Color(0xFF00FF41).copy(alpha = alpha) // Matrix green
    }

    private fun hsvToColor(h: Float, s: Float, v: Float): Color {
        val c = v * s
        val x = c * (1f - abs((h / 60f) % 2f - 1f))
        val m = v - c
        val (r, g, b) = when {
            h < 60f -> Triple(c, x, 0f)
            h < 120f -> Triple(x, c, 0f)
            h < 180f -> Triple(0f, c, x)
            h < 240f -> Triple(0f, x, c)
            h < 300f -> Triple(x, 0f, c)
            else -> Triple(c, 0f, x)
        }
        return Color(r + m, g + m, b + m, 1f)
    }

    private fun loadMode(): Mode {
        val name = prefs.getString(KEY_MODE, Mode.OFF.name)
        return try {
            Mode.valueOf(name ?: Mode.OFF.name)
        } catch (_: IllegalArgumentException) {
            Mode.OFF
        }
    }

    private fun loadHue(): Float {
        return prefs.getFloat(KEY_HUE, 170f) // Default: cyan-ish (#00D4AA ≈ 163°)
    }
}
