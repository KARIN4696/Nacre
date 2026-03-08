package space.manus.nacre.ime.input

import android.content.Context
import android.view.inputmethod.ExtractedTextRequest
import android.view.inputmethod.InputConnection
import org.json.JSONArray
import org.json.JSONObject

data class Snippet(
    val name: String,
    val template: String,
    val trigger: String? = null,
)

/**
 * Manages code snippets with tab-stop support.
 *
 * Template format:
 *   - `$0` marks the final cursor position
 *   - `${1:placeholder}` marks numbered tab stops with default text
 *   - Tab stops are visited in numeric order via [nextTabStop]
 */
class SnippetEngine(private val context: Context) {

    val snippets: MutableList<Snippet> = mutableListOf()

    /** Track active tab-stop session state. */
    private var activeTabStops: MutableList<TabStop> = mutableListOf()
    private var currentTabIndex: Int = -1
    private var insertedText: String = ""
    private var insertOffset: Int = 0

    init {
        loadSnippets(context)
        if (snippets.isEmpty()) {
            snippets.addAll(defaultSnippets())
        }
    }

    fun addSnippet(snippet: Snippet) {
        snippets.add(snippet)
    }

    fun removeSnippet(name: String) {
        snippets.removeAll { it.name == name }
    }

    fun getSnippet(name: String): Snippet? {
        return snippets.find { it.name == name }
    }

    /**
     * Insert a snippet's template into the editor.
     * Resolves tab stops and positions the cursor at `$0` or at the end.
     */
    fun insertSnippet(snippet: Snippet, ic: InputConnection) {
        ic.beginBatchEdit()
        try {
            // Parse tab stops from template
            val parsed = parseTemplate(snippet.template)
            val cleanText = parsed.first
            val tabStops = parsed.second

            // Get current cursor position for offset tracking
            val extracted = ic.getExtractedText(ExtractedTextRequest(), 0)
            insertOffset = extracted?.selectionStart ?: 0

            // Commit the expanded text
            ic.commitText(cleanText, 1)
            insertedText = cleanText

            // Set up tab stop navigation
            activeTabStops.clear()
            activeTabStops.addAll(tabStops.sortedBy { it.index })
            currentTabIndex = -1

            // Position cursor: if there are numbered tab stops, go to the first one
            val firstNumbered = activeTabStops.firstOrNull { it.index > 0 }
            val finalStop = activeTabStops.firstOrNull { it.index == 0 }

            if (firstNumbered != null) {
                currentTabIndex = 0
                val pos = insertOffset + firstNumbered.offset
                ic.setSelection(pos, pos + firstNumbered.length)
            } else if (finalStop != null) {
                val pos = insertOffset + finalStop.offset
                ic.setSelection(pos, pos)
                clearSession()
            } else {
                // No tab stops; cursor stays at end
                clearSession()
            }
        } finally {
            ic.endBatchEdit()
        }
    }

    /**
     * Advance to the next tab stop. When all numbered stops are visited,
     * jump to `$0` (final position) and end the session.
     */
    fun nextTabStop(ic: InputConnection) {
        if (activeTabStops.isEmpty()) return

        currentTabIndex++
        val numberedStops = activeTabStops.filter { it.index > 0 }.sortedBy { it.index }
        val finalStop = activeTabStops.firstOrNull { it.index == 0 }

        if (currentTabIndex < numberedStops.size) {
            val stop = numberedStops[currentTabIndex]
            val pos = insertOffset + stop.offset
            ic.setSelection(pos, pos + stop.length)
        } else if (finalStop != null) {
            val pos = insertOffset + finalStop.offset
            ic.setSelection(pos, pos)
            clearSession()
        } else {
            clearSession()
        }
    }

    /** Whether a tab-stop session is currently active. */
    val hasActiveSession: Boolean
        get() = activeTabStops.isNotEmpty()

    private fun clearSession() {
        activeTabStops.clear()
        currentTabIndex = -1
        insertedText = ""
        insertOffset = 0
    }

    // --- Template parsing ---

    /**
     * Parse a template string, extracting tab stops and returning
     * the clean text plus a list of [TabStop] positions.
     */
    private fun parseTemplate(template: String): Pair<String, List<TabStop>> {
        val stops = mutableListOf<TabStop>()
        val result = StringBuilder()
        var i = 0

        while (i < template.length) {
            when {
                // ${N:placeholder}
                i < template.length - 1 && template[i] == '$' && template[i + 1] == '{' -> {
                    val closeBrace = template.indexOf('}', i + 2)
                    if (closeBrace == -1) {
                        result.append(template[i])
                        i++
                        continue
                    }
                    val inner = template.substring(i + 2, closeBrace)
                    val colonPos = inner.indexOf(':')
                    if (colonPos != -1) {
                        val index = inner.substring(0, colonPos).toIntOrNull()
                        val placeholder = inner.substring(colonPos + 1)
                        if (index != null) {
                            stops.add(TabStop(index, result.length, placeholder.length))
                            result.append(placeholder)
                        } else {
                            result.append(template.substring(i, closeBrace + 1))
                        }
                    } else {
                        val index = inner.toIntOrNull()
                        if (index != null) {
                            stops.add(TabStop(index, result.length, 0))
                        } else {
                            result.append(template.substring(i, closeBrace + 1))
                        }
                    }
                    i = closeBrace + 1
                }
                // $0 (final cursor)
                i < template.length - 1 && template[i] == '$' && template[i + 1] == '0' -> {
                    stops.add(TabStop(0, result.length, 0))
                    i += 2
                }
                else -> {
                    result.append(template[i])
                    i++
                }
            }
        }

        return Pair(result.toString(), stops)
    }

    // --- Persistence ---

    fun saveSnippets(context: Context) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val json = JSONArray()
        for (snippet in snippets) {
            val obj = JSONObject()
            obj.put("name", snippet.name)
            obj.put("template", snippet.template)
            obj.put("trigger", snippet.trigger)
            json.put(obj)
        }
        prefs.edit().putString(KEY_SNIPPETS, json.toString()).apply()
    }

    fun loadSnippets(context: Context) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val raw = prefs.getString(KEY_SNIPPETS, null) ?: return
        try {
            val json = JSONArray(raw)
            snippets.clear()
            for (i in 0 until json.length()) {
                val obj = json.getJSONObject(i)
                snippets.add(
                    Snippet(
                        name = obj.getString("name"),
                        template = obj.getString("template"),
                        trigger = obj.optString("trigger", null),
                    ),
                )
            }
        } catch (_: Exception) {
            // Corrupt data; keep defaults
        }
    }

    // --- Default snippets ---

    private fun defaultSnippets(): List<Snippet> = listOf(
        Snippet(
            name = "git commit",
            template = "git commit -m \"\$0\"",
            trigger = ";gc",
        ),
        Snippet(
            name = "for loop",
            template = "for \${1:i} in \${2:list}; do\n  \$0\ndone",
            trigger = ";for",
        ),
        Snippet(
            name = "if block",
            template = "if [ \${1:condition} ]; then\n  \$0\nfi",
            trigger = ";if",
        ),
        Snippet(
            name = "function",
            template = "\${1:func_name}() {\n  \$0\n}",
            trigger = ";fn",
        ),
    )

    private data class TabStop(
        val index: Int,
        val offset: Int,
        val length: Int,
    )

    companion object {
        private const val PREFS_NAME = "nacre_snippets"
        private const val KEY_SNIPPETS = "snippets_json"
    }
}
