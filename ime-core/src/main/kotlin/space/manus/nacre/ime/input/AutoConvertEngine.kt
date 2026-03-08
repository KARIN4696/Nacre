package space.manus.nacre.ime.input

import android.content.Context
import android.view.inputmethod.InputConnection
import org.json.JSONArray
import org.json.JSONObject

data class ConvertRule(
    val pattern: String,
    val replacement: String,
    val enabled: Boolean = true,
)

/**
 * Auto-converts typed patterns into special characters or kaomoji.
 * Can be toggled off during coding sessions where literal text is needed.
 */
class AutoConvertEngine(private val context: Context) {

    val rules: MutableList<ConvertRule> = mutableListOf()

    /** Master toggle — when false, no conversions are performed. */
    var isEnabled: Boolean = true

    init {
        loadRules(context)
        if (rules.isEmpty()) {
            rules.addAll(defaultRules())
        }
    }

    fun addRule(rule: ConvertRule) {
        rules.add(rule)
    }

    fun removeRule(pattern: String) {
        rules.removeAll { it.pattern == pattern }
    }

    fun toggleRule(pattern: String) {
        val index = rules.indexOfFirst { it.pattern == pattern }
        if (index != -1) {
            val rule = rules[index]
            rules[index] = rule.copy(enabled = !rule.enabled)
        }
    }

    /**
     * Check if the recently typed text ends with any active rule pattern.
     * If a match is found, delete the pattern from the editor and insert
     * the replacement.
     *
     * @param text The recent input buffer (typically the last N characters).
     * @param ic The active InputConnection.
     * @return `true` if a conversion was performed.
     */
    fun checkAndConvert(text: String, ic: InputConnection): Boolean {
        if (!isEnabled) return false

        for (rule in rules) {
            if (!rule.enabled) continue
            if (text.endsWith(rule.pattern)) {
                ic.beginBatchEdit()
                try {
                    // Delete the trigger pattern from the committed text
                    ic.deleteSurroundingText(rule.pattern.length, 0)
                    // Insert the replacement
                    ic.commitText(rule.replacement, 1)
                } finally {
                    ic.endBatchEdit()
                }
                return true
            }
        }
        return false
    }

    // --- Persistence ---

    fun saveRules(context: Context) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val json = JSONArray()
        for (rule in rules) {
            val obj = JSONObject()
            obj.put("pattern", rule.pattern)
            obj.put("replacement", rule.replacement)
            obj.put("enabled", rule.enabled)
            json.put(obj)
        }
        prefs.edit()
            .putString(KEY_RULES, json.toString())
            .putBoolean(KEY_ENABLED, isEnabled)
            .apply()
    }

    fun loadRules(context: Context) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        isEnabled = prefs.getBoolean(KEY_ENABLED, true)
        val raw = prefs.getString(KEY_RULES, null) ?: return
        try {
            val json = JSONArray(raw)
            rules.clear()
            for (i in 0 until json.length()) {
                val obj = json.getJSONObject(i)
                rules.add(
                    ConvertRule(
                        pattern = obj.getString("pattern"),
                        replacement = obj.getString("replacement"),
                        enabled = obj.optBoolean("enabled", true),
                    ),
                )
            }
        } catch (_: Exception) {
            // Corrupt data; keep defaults
        }
    }

    // --- Default rules ---

    private fun defaultRules(): List<ConvertRule> = listOf(
        ConvertRule("->", "\u2192"),    // →
        ConvertRule("!=", "\u2260"),    // ≠
        ConvertRule("<=", "\u2264"),    // ≤
        ConvertRule(">=", "\u2265"),    // ≥
        ConvertRule(":shrug:", "\u00AF\\_(\u30C4)_/\u00AF"),         // ¯\_(ツ)_/¯
        ConvertRule(":tableflip:", "(\u256F\u00B0\u25A1\u00B0)\u256F\uFE35 \u253B\u2501\u253B"), // (╯°□°)╯︵ ┻━┻
    )

    companion object {
        private const val PREFS_NAME = "nacre_auto_convert"
        private const val KEY_RULES = "rules_json"
        private const val KEY_ENABLED = "auto_convert_enabled"
    }
}
