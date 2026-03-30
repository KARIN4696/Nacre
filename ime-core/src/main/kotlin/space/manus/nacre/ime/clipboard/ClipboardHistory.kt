package space.manus.nacre.ime.clipboard

import android.content.ClipboardManager
import android.content.Context
import androidx.compose.runtime.mutableStateListOf
import org.json.JSONArray

/**
 * Manages a 5-item clipboard history ring buffer.
 * Persists to SharedPreferences as a JSON array.
 */
class ClipboardHistory(private val context: Context) {

    companion object {
        private const val PREFS_NAME = "nacre_clipboard"
        private const val KEY_HISTORY = "history"
        private const val MAX_ITEMS = 5
    }

    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    /** Observable history list for Compose UI */
    val items = mutableStateListOf<String>()

    private var clipboardManager: ClipboardManager? = null

    fun init() {
        loadHistory()
        clipboardManager = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
        clipboardManager?.addPrimaryClipChangedListener {
            val clip = clipboardManager?.primaryClip
            val text = clip?.getItemAt(0)?.text?.toString() ?: return@addPrimaryClipChangedListener
            if (text.isBlank()) return@addPrimaryClipChangedListener
            addToHistory(text)
        }
    }

    private fun addToHistory(text: String) {
        // Skip if already most recent
        if (items.isNotEmpty() && items[0] == text) return
        // Remove if already exists elsewhere
        items.remove(text)
        // Prepend
        items.add(0, text)
        // Cap at MAX_ITEMS
        while (items.size > MAX_ITEMS) {
            items.removeAt(items.lastIndex)
        }
        saveHistory()
    }

    private fun loadHistory() {
        val json = prefs.getString(KEY_HISTORY, "[]") ?: "[]"
        try {
            val arr = JSONArray(json)
            items.clear()
            for (i in 0 until arr.length().coerceAtMost(MAX_ITEMS)) {
                items.add(arr.getString(i))
            }
        } catch (_: Exception) {
            items.clear()
        }
    }

    private fun saveHistory() {
        val arr = JSONArray()
        for (item in items) {
            arr.put(item)
        }
        prefs.edit().putString(KEY_HISTORY, arr.toString()).apply()
    }
}
