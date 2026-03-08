package space.manus.nacre.ime.input

import android.content.Context
import android.view.KeyEvent
import android.view.inputmethod.InputConnection
import kotlinx.coroutines.delay
import org.json.JSONArray
import org.json.JSONObject

data class Macro(
    val name: String,
    val keys: List<MacroStep>,
    val trigger: String? = null,
)

sealed class MacroStep {
    data class TextStep(val text: String) : MacroStep()
    data class KeyCodeStep(
        val code: Int,
        val ctrl: Boolean = false,
        val alt: Boolean = false,
        val shift: Boolean = false,
    ) : MacroStep()
    data class DelayStep(val ms: Long) : MacroStep()
    data object EnterStep : MacroStep()
}

class MacroEngine(private val context: Context) {

    val macros: MutableList<Macro> = mutableListOf()

    init {
        loadMacros(context)
        if (macros.isEmpty()) {
            macros.addAll(defaultMacros())
        }
    }

    fun addMacro(macro: Macro) {
        macros.add(macro)
    }

    fun removeMacro(name: String) {
        macros.removeAll { it.name == name }
    }

    fun getMacro(name: String): Macro? {
        return macros.find { it.name == name }
    }

    /**
     * Check if the recently typed text matches any macro trigger.
     */
    fun checkTrigger(text: String): Macro? {
        return macros.find { macro ->
            macro.trigger != null && text.endsWith(macro.trigger)
        }
    }

    /**
     * Execute a macro by replaying its steps into the InputConnection.
     * Uses beginBatchEdit/endBatchEdit to group the edits atomically.
     */
    suspend fun executeMacro(macro: Macro, ic: InputConnection) {
        ic.beginBatchEdit()
        try {
            for (step in macro.keys) {
                when (step) {
                    is MacroStep.TextStep -> {
                        ic.commitText(step.text, 1)
                    }
                    is MacroStep.KeyCodeStep -> {
                        val now = System.currentTimeMillis()
                        var metaState = 0
                        if (step.ctrl) metaState = metaState or KeyEvent.META_CTRL_ON
                        if (step.alt) metaState = metaState or KeyEvent.META_ALT_ON
                        if (step.shift) metaState = metaState or KeyEvent.META_SHIFT_ON
                        ic.sendKeyEvent(
                            KeyEvent(now, now, KeyEvent.ACTION_DOWN, step.code, 0, metaState),
                        )
                        ic.sendKeyEvent(
                            KeyEvent(now, now, KeyEvent.ACTION_UP, step.code, 0, metaState),
                        )
                    }
                    is MacroStep.DelayStep -> {
                        delay(step.ms)
                    }
                    is MacroStep.EnterStep -> {
                        ic.sendKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_ENTER))
                        ic.sendKeyEvent(KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_ENTER))
                    }
                }
            }
        } finally {
            ic.endBatchEdit()
        }
    }

    // --- Persistence ---

    fun saveMacros(context: Context) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val json = JSONArray()
        for (macro in macros) {
            json.put(macroToJson(macro))
        }
        prefs.edit().putString(KEY_MACROS, json.toString()).apply()
    }

    fun loadMacros(context: Context) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val raw = prefs.getString(KEY_MACROS, null) ?: return
        try {
            val json = JSONArray(raw)
            macros.clear()
            for (i in 0 until json.length()) {
                macros.add(jsonToMacro(json.getJSONObject(i)))
            }
        } catch (_: Exception) {
            // Corrupt data; keep defaults
        }
    }

    // --- JSON serialization ---

    private fun macroToJson(macro: Macro): JSONObject {
        val obj = JSONObject()
        obj.put("name", macro.name)
        obj.put("trigger", macro.trigger)
        val steps = JSONArray()
        for (step in macro.keys) {
            val s = JSONObject()
            when (step) {
                is MacroStep.TextStep -> {
                    s.put("type", "text")
                    s.put("text", step.text)
                }
                is MacroStep.KeyCodeStep -> {
                    s.put("type", "keycode")
                    s.put("code", step.code)
                    s.put("ctrl", step.ctrl)
                    s.put("alt", step.alt)
                    s.put("shift", step.shift)
                }
                is MacroStep.DelayStep -> {
                    s.put("type", "delay")
                    s.put("ms", step.ms)
                }
                is MacroStep.EnterStep -> {
                    s.put("type", "enter")
                }
            }
            steps.put(s)
        }
        obj.put("keys", steps)
        return obj
    }

    private fun jsonToMacro(obj: JSONObject): Macro {
        val name = obj.getString("name")
        val trigger = obj.optString("trigger", null)
        val stepsArr = obj.getJSONArray("keys")
        val steps = mutableListOf<MacroStep>()
        for (i in 0 until stepsArr.length()) {
            val s = stepsArr.getJSONObject(i)
            steps.add(
                when (s.getString("type")) {
                    "text" -> MacroStep.TextStep(s.getString("text"))
                    "keycode" -> MacroStep.KeyCodeStep(
                        code = s.getInt("code"),
                        ctrl = s.optBoolean("ctrl", false),
                        alt = s.optBoolean("alt", false),
                        shift = s.optBoolean("shift", false),
                    )
                    "delay" -> MacroStep.DelayStep(s.getLong("ms"))
                    "enter" -> MacroStep.EnterStep
                    else -> MacroStep.TextStep("")
                },
            )
        }
        return Macro(name = name, keys = steps, trigger = trigger)
    }

    // --- Default macros ---

    private fun defaultMacros(): List<Macro> = listOf(
        Macro(
            name = "git status",
            keys = listOf(MacroStep.TextStep("git status"), MacroStep.EnterStep),
            trigger = ";gs",
        ),
        Macro(
            name = "cd ..",
            keys = listOf(MacroStep.TextStep("cd .."), MacroStep.EnterStep),
            trigger = ";cd",
        ),
        Macro(
            name = "pipe grep",
            keys = listOf(MacroStep.TextStep("| grep ")),
            trigger = ";gr",
        ),
        Macro(
            name = "and-then",
            keys = listOf(MacroStep.TextStep(" && ")),
            trigger = ";&&",
        ),
        Macro(
            name = "sudo !!",
            keys = listOf(MacroStep.TextStep("sudo !!"), MacroStep.EnterStep),
            trigger = ";su",
        ),
    )

    companion object {
        private const val PREFS_NAME = "nacre_macros"
        private const val KEY_MACROS = "macros_json"
    }
}
