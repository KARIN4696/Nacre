package space.manus.nacre.ai.cloud

/**
 * Provider-agnostic interface for cloud LLM dictation cleanup.
 *
 * Each implementation wraps one vendor (Qwen Max, Gemini Pro, DeepSeek V3…).
 * Calls are synchronous and blocking — the caller runs them on a worker thread.
 */
interface CloudLlmRefiner {
    /** Human-readable name used only for diagnostic logging. */
    val name: String

    /**
     * Attempt to refine raw voice transcription. Returns [Result.success] with
     * the cleaned text on success, or [Result.failure] with the provider's
     * error (network, auth, rate-limit, parse). Failure is silent for the
     * caller — the chain decides whether to try the next provider.
     */
    fun refine(rawText: String, instruction: String, timeoutMs: Int): Result<String>

    /** Whether this refiner has an API key configured and is worth trying. */
    fun isConfigured(): Boolean
}
