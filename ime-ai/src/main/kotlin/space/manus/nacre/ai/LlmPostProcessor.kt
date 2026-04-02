package space.manus.nacre.ai

import android.util.Log
import java.net.HttpURLConnection
import java.net.URL

/**
 * Post-processes raw voice transcription via local LLM (llama-server).
 * Converts colloquial speech to clean written Japanese.
 */
object LlmPostProcessor {
    private const val TAG = "LlmPostProcessor"
    private const val SERVER_URL = "http://127.0.0.1:8080/v1/chat/completions"
    private const val TIMEOUT_MS = 15_000
    private const val SYSTEM_PROMPT = "音声入力の生テキストを書き言葉に整形。フィラー除去、句読点追加。整形後のテキストのみ出力。"

    /**
     * Refine raw transcription text via LLM.
     * Returns refined text, or original text if LLM is unavailable.
     */
    fun refine(rawText: String): String {
        if (rawText.isBlank()) return rawText
        try {
            val requestBody = """
                {"messages":[
                    {"role":"system","content":"$SYSTEM_PROMPT"},
                    {"role":"user","content":${jsonEscape(rawText)}}
                ],"temperature":0.3,"max_tokens":${rawText.length * 3}}
            """.trimIndent()

            val conn = (URL(SERVER_URL).openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                setRequestProperty("Content-Type", "application/json")
                connectTimeout = 2000
                readTimeout = TIMEOUT_MS
                doOutput = true
            }
            conn.outputStream.use { it.write(requestBody.toByteArray()) }

            if (conn.responseCode != 200) {
                Log.w(TAG, "LLM server returned ${conn.responseCode}")
                return rawText
            }

            val response = conn.inputStream.bufferedReader().readText()
            // Simple JSON extraction without dependency
            val contentStart = response.indexOf("\"content\":\"") + 11
            val contentEnd = response.indexOf("\"", contentStart + 1)
            if (contentStart > 10 && contentEnd > contentStart) {
                val refined = response.substring(contentStart, contentEnd)
                    .replace("\\n", "\n")
                    .replace("\\\"", "\"")
                    .replace("\\\\", "\\")
                Log.i(TAG, "Refined: '$rawText' → '$refined'")
                return refined.ifBlank { rawText }
            }
        } catch (e: java.net.ConnectException) {
            Log.d(TAG, "LLM server not running, using raw text")
        } catch (e: java.net.SocketTimeoutException) {
            Log.w(TAG, "LLM server timeout")
        } catch (e: Exception) {
            Log.w(TAG, "LLM post-processing failed", e)
        }
        return rawText
    }

    /**
     * Check if llama-server is running and responding.
     */
    fun isAvailable(): Boolean {
        return try {
            val conn = URL("http://127.0.0.1:8080/health").openConnection() as HttpURLConnection
            conn.connectTimeout = 1000
            conn.readTimeout = 1000
            val ok = conn.responseCode == 200
            conn.disconnect()
            ok
        } catch (_: Exception) { false }
    }

    private fun jsonEscape(s: String): String {
        val sb = StringBuilder("\"")
        for (c in s) {
            when (c) {
                '"' -> sb.append("\\\"")
                '\\' -> sb.append("\\\\")
                '\n' -> sb.append("\\n")
                '\r' -> sb.append("\\r")
                '\t' -> sb.append("\\t")
                else -> sb.append(c)
            }
        }
        sb.append("\"")
        return sb.toString()
    }
}
