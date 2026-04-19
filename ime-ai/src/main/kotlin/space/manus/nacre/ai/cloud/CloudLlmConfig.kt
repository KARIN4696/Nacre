package space.manus.nacre.ai.cloud

import android.content.Context

/**
 * Persistent storage for cloud LLM API keys. Keys live in a dedicated
 * SharedPreferences file and never leave the device except as the HTTPS
 * Authorization header on the specific vendor's endpoint.
 */
object CloudLlmConfig {
    private const val PREFS = "nacre_cloud_llm"
    private const val KEY_QWEN = "qwen_max_key"
    private const val KEY_GEMINI = "gemini_key"
    private const val KEY_DEEPSEEK = "deepseek_key"

    private fun prefs(ctx: Context) = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun qwenMaxKey(ctx: Context): String? = prefs(ctx).getString(KEY_QWEN, null)
    fun geminiKey(ctx: Context): String? = prefs(ctx).getString(KEY_GEMINI, null)
    fun deepSeekKey(ctx: Context): String? = prefs(ctx).getString(KEY_DEEPSEEK, null)

    fun setQwenMaxKey(ctx: Context, value: String) {
        prefs(ctx).edit().putString(KEY_QWEN, value.trim().ifEmpty { null }).apply()
    }

    fun setGeminiKey(ctx: Context, value: String) {
        prefs(ctx).edit().putString(KEY_GEMINI, value.trim().ifEmpty { null }).apply()
    }

    fun setDeepSeekKey(ctx: Context, value: String) {
        prefs(ctx).edit().putString(KEY_DEEPSEEK, value.trim().ifEmpty { null }).apply()
    }

    fun anyConfigured(ctx: Context): Boolean =
        !qwenMaxKey(ctx).isNullOrBlank() ||
            !geminiKey(ctx).isNullOrBlank() ||
            !deepSeekKey(ctx).isNullOrBlank()
}
