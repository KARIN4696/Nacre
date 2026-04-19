package space.manus.nacre.ai.cloud

import android.content.Context

/**
 * Builds the production chain of cloud refiners in priority order.
 *
 * Quality-first ordering (highest quality → highest quota → fallback):
 * 1. Qwen 2.5 Max (Alibaba DashScope) — S-tier Japanese, limited free quota
 * 2. Gemini 2.5 Pro (Google AI Studio) — S-tier, 50/day free
 * 3. DeepSeek V3 (DeepSeek direct) — S/A-tier, effectively unlimited free
 *
 * Each step is skipped if its key isn't set, so the chain degrades gracefully
 * as the user configures one, two, or all three providers. If none are
 * configured the chain returns no refiners and the caller falls through to
 * local (offline) Qwen 1.5B.
 */
object RefinerFactory {
    fun build(ctx: Context): List<CloudLlmRefiner> {
        val all = listOf(
            OpenAICompatibleRefiner(
                name = "Qwen 2.5 Max",
                // International (non-CN) endpoint — OpenAI-compatible mode.
                endpoint = "https://dashscope-intl.aliyuncs.com/compatible-mode/v1/chat/completions",
                model = "qwen-max",
                apiKeyProvider = { CloudLlmConfig.qwenMaxKey(ctx) },
            ),
            OpenAICompatibleRefiner(
                name = "Gemini 2.5 Pro",
                endpoint = "https://generativelanguage.googleapis.com/v1beta/openai/chat/completions",
                model = "gemini-2.5-pro",
                apiKeyProvider = { CloudLlmConfig.geminiKey(ctx) },
            ),
            OpenAICompatibleRefiner(
                name = "DeepSeek V3",
                endpoint = "https://api.deepseek.com/v1/chat/completions",
                model = "deepseek-chat",
                apiKeyProvider = { CloudLlmConfig.deepSeekKey(ctx) },
            ),
        )
        return all.filter { it.isConfigured() }
    }
}
