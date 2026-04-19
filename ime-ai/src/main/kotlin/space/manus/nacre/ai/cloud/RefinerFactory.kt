package space.manus.nacre.ai.cloud

import android.content.Context

/**
 * Builds the production chain of cloud refiners in priority order.
 *
 * Empirical notes (2026-04):
 * - Gemini 2.5 Pro has `limit: 0` on the free tier — it is effectively paid-only.
 *   Use Gemini 2.5 Flash, which does have a real free quota (~1500/day).
 * - OpenRouter `:free` models share upstream capacity pools. Individual models
 *   go to HTTP 429 during peak hours. Putting multiple OpenRouter models in
 *   the chain lets a single user key cycle across pools.
 *
 * Priority order:
 * 1. OpenRouter Qwen3 Next 80B :free  (S-tier JP when available)
 * 2. OpenRouter GPT-OSS 120B :free    (same key, different pool)
 * 3. OpenRouter Llama 3.3 70B :free   (same key, yet another pool)
 * 4. Gemini 2.5 Flash                 (different vendor quota, ~1500/day)
 * 5. DeepSeek V3                      (effectively unlimited)
 */
object RefinerFactory {
    fun build(ctx: Context): List<CloudLlmRefiner> {
        val openRouterKey = { CloudLlmConfig.qwenMaxKey(ctx) }
        val all = listOf(
            OpenAICompatibleRefiner(
                name = "OpenRouter Qwen3 Next 80B",
                endpoint = OPENROUTER_ENDPOINT,
                model = "qwen/qwen3-next-80b-a3b-instruct:free",
                apiKeyProvider = openRouterKey,
            ),
            OpenAICompatibleRefiner(
                name = "OpenRouter GPT-OSS 120B",
                endpoint = OPENROUTER_ENDPOINT,
                model = "openai/gpt-oss-120b:free",
                apiKeyProvider = openRouterKey,
            ),
            OpenAICompatibleRefiner(
                name = "OpenRouter Llama 3.3 70B",
                endpoint = OPENROUTER_ENDPOINT,
                model = "meta-llama/llama-3.3-70b-instruct:free",
                apiKeyProvider = openRouterKey,
            ),
            OpenAICompatibleRefiner(
                name = "Gemini 2.5 Flash",
                endpoint = "https://generativelanguage.googleapis.com/v1beta/openai/chat/completions",
                model = "gemini-2.5-flash",
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

    private const val OPENROUTER_ENDPOINT = "https://openrouter.ai/api/v1/chat/completions"
}
