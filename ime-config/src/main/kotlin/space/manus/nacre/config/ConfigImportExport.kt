package space.manus.nacre.config

import android.content.Context
import android.net.Uri
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

object ConfigImportExport {

    // ---- Data classes ----

    data class MacroData(
        val trigger: String,
        val expansion: String,
    )

    data class SnippetData(
        val name: String,
        val content: String,
    )

    data class RuleData(
        val pattern: String,
        val replacement: String,
    )

    data class ThemeData(
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

    data class NacreConfig(
        val presetName: String,
        val customKeys: Map<String, String>?,
        val macros: List<MacroData>?,
        val snippets: List<SnippetData>?,
        val autoConvertRules: List<RuleData>?,
        val theme: ThemeData?,
    )

    // ---- Export ----

    fun exportToJson(config: NacreConfig): String {
        val root = JSONObject()
        root.put("presetName", config.presetName)

        config.customKeys?.let { keys ->
            val obj = JSONObject()
            keys.forEach { (k, v) -> obj.put(k, v) }
            root.put("customKeys", obj)
        }

        config.macros?.let { list ->
            val arr = JSONArray()
            list.forEach { m ->
                val obj = JSONObject()
                obj.put("trigger", m.trigger)
                obj.put("expansion", m.expansion)
                arr.put(obj)
            }
            root.put("macros", arr)
        }

        config.snippets?.let { list ->
            val arr = JSONArray()
            list.forEach { s ->
                val obj = JSONObject()
                obj.put("name", s.name)
                obj.put("content", s.content)
                arr.put(obj)
            }
            root.put("snippets", arr)
        }

        config.autoConvertRules?.let { list ->
            val arr = JSONArray()
            list.forEach { r ->
                val obj = JSONObject()
                obj.put("pattern", r.pattern)
                obj.put("replacement", r.replacement)
                arr.put(obj)
            }
            root.put("autoConvertRules", arr)
        }

        config.theme?.let { t ->
            val obj = JSONObject()
            obj.put("name", t.name)
            obj.put("background", t.background)
            obj.put("surface", t.surface)
            obj.put("keyBackground", t.keyBackground)
            obj.put("keyBackgroundPressed", t.keyBackgroundPressed)
            obj.put("keyText", t.keyText)
            obj.put("keyTextSwipe", t.keyTextSwipe)
            obj.put("accent", t.accent)
            obj.put("candidateBackground", t.candidateBackground)
            obj.put("candidateSelectedBackground", t.candidateSelectedBackground)
            root.put("theme", obj)
        }

        return root.toString(2)
    }

    // ---- Import ----

    fun importFromJson(json: String): NacreConfig? {
        return try {
            val root = JSONObject(json)
            val presetName = root.getString("presetName")

            val customKeys: Map<String, String>? = if (root.has("customKeys")) {
                val obj = root.getJSONObject("customKeys")
                val map = mutableMapOf<String, String>()
                obj.keys().forEach { key -> map[key] = obj.getString(key) }
                map
            } else null

            val macros: List<MacroData>? = if (root.has("macros")) {
                val arr = root.getJSONArray("macros")
                (0 until arr.length()).map { i ->
                    val obj = arr.getJSONObject(i)
                    MacroData(obj.getString("trigger"), obj.getString("expansion"))
                }
            } else null

            val snippets: List<SnippetData>? = if (root.has("snippets")) {
                val arr = root.getJSONArray("snippets")
                (0 until arr.length()).map { i ->
                    val obj = arr.getJSONObject(i)
                    SnippetData(obj.getString("name"), obj.getString("content"))
                }
            } else null

            val autoConvertRules: List<RuleData>? = if (root.has("autoConvertRules")) {
                val arr = root.getJSONArray("autoConvertRules")
                (0 until arr.length()).map { i ->
                    val obj = arr.getJSONObject(i)
                    RuleData(obj.getString("pattern"), obj.getString("replacement"))
                }
            } else null

            val theme: ThemeData? = if (root.has("theme")) {
                val obj = root.getJSONObject("theme")
                ThemeData(
                    name = obj.getString("name"),
                    background = obj.getLong("background"),
                    surface = obj.getLong("surface"),
                    keyBackground = obj.getLong("keyBackground"),
                    keyBackgroundPressed = obj.getLong("keyBackgroundPressed"),
                    keyText = obj.getLong("keyText"),
                    keyTextSwipe = obj.getLong("keyTextSwipe"),
                    accent = obj.getLong("accent"),
                    candidateBackground = obj.getLong("candidateBackground"),
                    candidateSelectedBackground = obj.getLong("candidateSelectedBackground"),
                )
            } else null

            NacreConfig(presetName, customKeys, macros, snippets, autoConvertRules, theme)
        } catch (e: Exception) {
            null
        }
    }

    // ---- File I/O ----

    fun exportToFile(context: Context, config: NacreConfig): Uri? {
        return try {
            val json = exportToJson(config)
            val file = File(context.cacheDir, "nacre-config.json")
            file.writeText(json, Charsets.UTF_8)
            Uri.fromFile(file)
        } catch (e: Exception) {
            null
        }
    }

    fun importFromUri(context: Context, uri: Uri): NacreConfig? {
        return try {
            val inputStream = context.contentResolver.openInputStream(uri) ?: return null
            val json = inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
            importFromJson(json)
        } catch (e: Exception) {
            null
        }
    }
}
