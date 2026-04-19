package space.manus.nacre.ai.cloud

import android.util.Log
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL

/**
 * Generic [CloudLlmRefiner] that talks to any OpenAI /v1/chat/completions -
 * compatible endpoint. Most 2026-era vendors (Alibaba DashScope, Google AI
 * Studio, DeepSeek, Groq, Together, OpenRouter, …) expose that shape, so one
 * implementation covers the whole chain.
 *
 * The caller is responsible for providing:
 * - an [endpoint] — full URL of the chat/completions endpoint
 * - the [model] id the vendor expects
 * - an [apiKeyProvider] that returns the user's personal key, or null
 */
class OpenAICompatibleRefiner(
    override val name: String,
    private val endpoint: String,
    private val model: String,
    private val apiKeyProvider: () -> String?,
) : CloudLlmRefiner {

    override fun isConfigured(): Boolean = !apiKeyProvider().isNullOrBlank()

    override fun refine(rawText: String, instruction: String, timeoutMs: Int): Result<String> {
        val key = apiKeyProvider()?.takeIf { it.isNotBlank() }
            ?: return Result.failure(IllegalStateException("no key for $name"))

        val body = buildRequestBody(model, instruction, rawText)
        val conn = (URL(endpoint).openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            connectTimeout = 4_000
            readTimeout = timeoutMs
            doOutput = true
            setRequestProperty("Content-Type", "application/json; charset=utf-8")
            setRequestProperty("Authorization", "Bearer $key")
        }
        return try {
            conn.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
            val code = conn.responseCode
            val stream: InputStream = if (code in 200..299) conn.inputStream else conn.errorStream
                ?: return Result.failure(IllegalStateException("$name HTTP $code (no body)"))
            val raw = stream.bufferedReader(Charsets.UTF_8).readText()
            if (code !in 200..299) {
                Log.w(TAG, "$name HTTP $code: ${raw.take(200)}")
                return Result.failure(IllegalStateException("$name HTTP $code"))
            }
            val content = parseContent(raw)
                ?: return Result.failure(IllegalStateException("$name: empty content"))
            Result.success(content.trim())
        } catch (e: Exception) {
            Log.w(TAG, "$name exception: ${e.javaClass.simpleName}: ${e.message}")
            Result.failure(e)
        } finally {
            try { conn.disconnect() } catch (_: Exception) {}
        }
    }

    /** Build the OpenAI-style chat/completions JSON request body. */
    private fun buildRequestBody(model: String, system: String, user: String): String {
        val sb = StringBuilder()
        sb.append("{\"model\":").append(jsonString(model))
        sb.append(",\"temperature\":0.1")
        sb.append(",\"messages\":[")
        sb.append("{\"role\":\"system\",\"content\":").append(jsonString(system)).append("},")
        sb.append("{\"role\":\"user\",\"content\":").append(jsonString(user)).append("}")
        sb.append("]}")
        return sb.toString()
    }

    /**
     * Extract `choices[0].message.content` from a chat/completions response
     * without pulling in a full JSON library. The payload is assumed UTF-8.
     */
    private fun parseContent(json: String): String? {
        // Find "content":" … "  inside the first choice's message.
        val marker = "\"content\":\""
        val start = json.indexOf(marker)
        if (start < 0) return null
        var i = start + marker.length
        val sb = StringBuilder()
        while (i < json.length) {
            val c = json[i]
            if (c == '\\' && i + 1 < json.length) {
                when (val next = json[i + 1]) {
                    'n' -> sb.append('\n')
                    't' -> sb.append('\t')
                    'r' -> sb.append('\r')
                    '"' -> sb.append('"')
                    '\\' -> sb.append('\\')
                    '/' -> sb.append('/')
                    'u' -> {
                        if (i + 5 < json.length) {
                            val hex = json.substring(i + 2, i + 6)
                            val codepoint = hex.toIntOrNull(16)
                            if (codepoint != null) sb.append(codepoint.toChar())
                            i += 4
                        }
                    }
                    else -> sb.append(next)
                }
                i += 2
            } else if (c == '"') {
                return sb.toString()
            } else {
                sb.append(c)
                i++
            }
        }
        return null
    }

    private fun jsonString(s: String): String {
        val sb = StringBuilder("\"")
        for (c in s) {
            when (c) {
                '"' -> sb.append("\\\"")
                '\\' -> sb.append("\\\\")
                '\n' -> sb.append("\\n")
                '\r' -> sb.append("\\r")
                '\t' -> sb.append("\\t")
                in '\u0000'..'\u001F' -> sb.append("\\u%04x".format(c.code))
                else -> sb.append(c)
            }
        }
        sb.append("\"")
        return sb.toString()
    }

    companion object {
        private const val TAG = "CloudRefiner"
    }
}
