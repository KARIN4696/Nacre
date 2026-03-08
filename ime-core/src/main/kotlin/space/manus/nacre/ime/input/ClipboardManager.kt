package space.manus.nacre.ime.input

import android.content.ClipboardManager as SystemClipboardManager
import android.content.Context
import androidx.compose.runtime.mutableStateListOf
import org.json.JSONArray
import org.json.JSONObject

data class ClipboardEntry(
    val text: String,
    val timestamp: Long,
    val isPassword: Boolean = false,
)

class ClipboardManager(private val context: Context) {

    companion object {
        private const val MAX_ENTRIES = 20
        private const val PREFS_NAME = "nacre_clipboard"
        private const val KEY_HISTORY = "history"
    }

    val history = mutableStateListOf<ClipboardEntry>()

    private val systemClipboard =
        context.getSystemService(Context.CLIPBOARD_SERVICE) as SystemClipboardManager

    private val clipChangedListener =
        SystemClipboardManager.OnPrimaryClipChangedListener {
            val clip = systemClipboard.primaryClip ?: return@OnPrimaryClipChangedListener
            if (clip.itemCount == 0) return@OnPrimaryClipChangedListener
            val text = clip.getItemAt(0).coerceToText(context)?.toString()
            if (!text.isNullOrBlank()) {
                addEntry(text, isPassword = false)
            }
        }

    fun startListening() {
        loadHistory(context)
        systemClipboard.addPrimaryClipChangedListener(clipChangedListener)
    }

    fun stopListening() {
        systemClipboard.removePrimaryClipChangedListener(clipChangedListener)
        saveHistory(context)
    }

    fun addEntry(text: String, isPassword: Boolean = false) {
        if (isPassword) return

        // Remove duplicate if already present
        history.removeAll { it.text == text }

        // Add to front
        history.add(0, ClipboardEntry(text = text, timestamp = System.currentTimeMillis()))

        // Trim to max
        while (history.size > MAX_ENTRIES) {
            history.removeAt(history.lastIndex)
        }
    }

    fun getEntry(index: Int): ClipboardEntry? {
        return history.getOrNull(index)
    }

    fun removeEntry(index: Int) {
        if (index in history.indices) {
            history.removeAt(index)
        }
    }

    fun clearHistory() {
        history.clear()
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit().remove(KEY_HISTORY).apply()
    }

    fun saveHistory(context: Context) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val array = JSONArray()
        for (entry in history) {
            if (entry.isPassword) continue
            val obj = JSONObject().apply {
                put("text", entry.text)
                put("timestamp", entry.timestamp)
            }
            array.put(obj)
        }
        prefs.edit().putString(KEY_HISTORY, array.toString()).apply()
    }

    fun loadHistory(context: Context) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val json = prefs.getString(KEY_HISTORY, null) ?: return
        try {
            val array = JSONArray(json)
            history.clear()
            for (i in 0 until array.length()) {
                val obj = array.getJSONObject(i)
                history.add(
                    ClipboardEntry(
                        text = obj.getString("text"),
                        timestamp = obj.getLong("timestamp"),
                    )
                )
            }
        } catch (_: Exception) {
            // Corrupted prefs — ignore
        }
    }
}
