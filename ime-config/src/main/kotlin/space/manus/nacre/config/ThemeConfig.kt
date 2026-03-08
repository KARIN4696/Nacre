package space.manus.nacre.config

import android.content.Context

data class NacreTheme(
    val name: String,
    val background: Long,
    val surface: Long,
    val keyBackground: Long,
    val keyBackgroundPressed: Long,
    val keyText: Long,
    val keyTextSwipe: Long,
    val accent: Long,
    val candidateBackground: Long,
    val candidateSelectedBackground: Long,
)

object ThemeProvider {

    private const val PREFS_NAME = "nacre_theme"
    private const val KEY_SELECTED = "selected_theme"

    val dark = NacreTheme(
        name = "Dark",
        background = 0xFF0F0F23,
        surface = 0xFF1A1A3A,
        keyBackground = 0xFF2A2A4A,
        keyBackgroundPressed = 0xFF3A3A5A,
        keyText = 0xFFE0E0E0,
        keyTextSwipe = 0xFF888899,
        accent = 0xFF00D4AA,
        candidateBackground = 0xFF1A1A3A,
        candidateSelectedBackground = 0xFF2A2A4A,
    )

    val light = NacreTheme(
        name = "Light",
        background = 0xFFF5F5F5,
        surface = 0xFFFFFFFF,
        keyBackground = 0xFFE0E0E0,
        keyBackgroundPressed = 0xFFD0D0D0,
        keyText = 0xFF1A1A1A,
        keyTextSwipe = 0xFF666666,
        accent = 0xFF00D4AA,
        candidateBackground = 0xFFFFFFFF,
        candidateSelectedBackground = 0xFFE8E8E8,
    )

    val amoled = NacreTheme(
        name = "AMOLED",
        background = 0xFF000000,
        surface = 0xFF0A0A0A,
        keyBackground = 0xFF1A1A1A,
        keyBackgroundPressed = 0xFF2A2A2A,
        keyText = 0xFFE0E0E0,
        keyTextSwipe = 0xFF666666,
        accent = 0xFF00D4AA,
        candidateBackground = 0xFF0A0A0A,
        candidateSelectedBackground = 0xFF1A1A1A,
    )

    val themes: List<NacreTheme> = listOf(dark, light, amoled)

    fun getTheme(name: String): NacreTheme =
        themes.find { it.name.equals(name, ignoreCase = true) } ?: dark

    fun saveSelectedTheme(context: Context, themeName: String) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_SELECTED, themeName)
            .apply()
    }

    fun loadSelectedTheme(context: Context): NacreTheme {
        val name = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getString(KEY_SELECTED, "Dark") ?: "Dark"
        return getTheme(name)
    }
}
