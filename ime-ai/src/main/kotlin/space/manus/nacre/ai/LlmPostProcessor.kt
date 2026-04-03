package space.manus.nacre.ai

import android.util.Log
import java.net.HttpURLConnection
import java.net.URL

/**
 * Post-processes raw voice transcription.
 *
 * Two-stage processing:
 * 1. quickClean() — instant rule-based cleanup (spaces, punctuation, fillers)
 * 2. refine() — LLM-based refinement (misrecognition fix, rephrasing) via llama-server
 */
object LlmPostProcessor {
    private const val TAG = "LlmPostProcessor"
    private const val SERVER_URL = "http://127.0.0.1:8080/v1/chat/completions"
    private const val TIMEOUT_MS = 30_000
    private const val SYSTEM_PROMPT = "あなたは音声入力テキストの整形AIです。以下の規則に従い整形後テキストのみ出力せよ。" +
        "1.不要なスペースを全て除去 " +
        "2.句読点:文末に「。」、列挙・接続に「、」を適切配置。不要な句点は除去 " +
        "3.フィラー(えー,あの,まあ,うーん等)を除去 " +
        "4.言い間違い:言い直しは後の正しい方を採用(例:「かいすい…解析」→「解析」) " +
        "5.誤変換:文脈から正しい漢字に修正(例:「回水」→「解析」「繁映」→「反映」「レレム」→「LLM」) " +
        "6.冗長な繰り返しを1回にまとめる " +
        "7.意味を保ったまま自然な書き言葉に整える " +
        "8.疑問文は「？」で終える"

    // Fillers sorted longest-first to avoid partial matches
    private val FILLERS = listOf(
        "えーっと", "えっと", "えーと", "あのー", "そのー", "ええと",
        "えー", "あー", "うーん", "あの", "まあ", "なんか", "こう",
        "その", "うん", "ん",
    ).sortedByDescending { it.length }

    // Technical term dictionary (katakana → proper notation)
    private val TECH_TERMS = mapOf(
        "エーピーアイ" to "API", "エルエルエム" to "LLM",
        "ジーピーティー" to "GPT", "チャットジーピーティー" to "ChatGPT",
        "ピーティーワイ" to "PTY", "ジェイソン" to "JSON",
        "エイチティーティーピー" to "HTTP", "ユーアールエル" to "URL",
        "エスキューエル" to "SQL", "ギットハブ" to "GitHub", "ギット" to "Git",
        "タイプスクリプト" to "TypeScript", "コトリン" to "Kotlin",
        "リアクト" to "React", "アンドロイド" to "Android",
        "エーピーケー" to "APK", "えーあい" to "AI",
        "ウィスパー" to "Whisper", "シェルパ" to "sherpa",
        "センスボイス" to "SenseVoice", "タイプレス" to "Typeless",
    ).entries.sortedByDescending { it.key.length }

    // Particles that continue a clause (「。」 before these = false sentence break)
    private val CONTINUATION_PARTICLES = Regex(
        "。([がはをにでともへ]|より|から|まで|けど|けれど|ので|のに|たら|ば|て|ながら|つつ|し|ところ|って)"
    )

    // Question-ending patterns
    private val QUESTION_ENDINGS = Regex(
        "(ですか|ますか|でしょうか|かな|よね|だろう|じゃない|でしょ|なの|ないの|のか|んですか|ませんか)。"
    )

    /**
     * Instant rule-based cleanup. No network, no delay.
     *
     * Processing order:
     * A. Artifact removal (leading ？, fillers)
     * B. Technical term dictionary
     * C. Half-width space removal (Japanese context)
     * D. Mid-sentence 「。」 fix (before continuation particles)
     * E. Duplicate phrase removal
     * F. Punctuation cleanup
     * G. Question detection
     */
    fun quickClean(rawText: String): String {
        if (rawText.isBlank()) return rawText
        var text = rawText

        // A. Remove leading/trailing ？ artifacts
        text = text.trimStart('？', '?')

        // A. Remove fillers
        for (filler in FILLERS) {
            text = text.replace(filler, "")
        }

        // B. Technical term dictionary
        for ((kana, term) in TECH_TERMS) {
            text = text.replace(kana, term)
        }

        // C. Remove half-width spaces between Japanese/CJK characters
        text = text.replace(
            Regex("([\\u3000-\\u9FFF\\uF900-\\uFAFF\\uFF00-\\uFFEF])\\s+([\\u3000-\\u9FFF\\uF900-\\uFAFF\\uFF00-\\uFFEF])"),
            "$1$2"
        )

        // D. Remove false mid-sentence 「。」 before continuation particles
        text = text.replace(CONTINUATION_PARTICLES, "$1")

        // E. Duplicate phrase removal (immediate repetition of 2-8 char phrases)
        text = text.replace(Regex("(.{2,8})\\1"), "$1")

        // F. Punctuation cleanup
        text = text.replace("。。", "。")
            .replace("、、", "、")
            .replace("？？", "？")
            .replace("。？", "？")
            .replace("。、", "、")

        // F. Collapse multiple spaces
        text = text.replace(Regex(" +"), " ")

        // F. Remove leading punctuation artifacts
        text = text.trimStart('。', '、', ' ').trim()

        // G. Question detection: 「ですか。」→「ですか？」
        text = text.replace(QUESTION_ENDINGS, "$1？")

        // G. Ensure question sentences with interrogative words end with ？
        text = text.replace(
            Regex("((?:何|どう|いつ|どこ|どれ|なぜ|なんで|どうして)[^。？]{0,30})。"),
            "$1？"
        )

        writeDiag("quickClean: '${rawText.take(60)}' → '${text.take(60)}'")
        return text
    }

    /**
     * Stage 2: LLM-based refinement via local llama-server.
     * Returns refined text, or original text if LLM is unavailable.
     */
    fun refine(rawText: String): String {
        if (rawText.isBlank()) return rawText
        writeDiag("refine() called: '${rawText.take(80)}'")
        try {
            val requestBody = """
                {"messages":[
                    {"role":"system","content":"$SYSTEM_PROMPT"},
                    {"role":"user","content":${jsonEscape(rawText)}}
                ],"temperature":0.3,"max_tokens":${rawText.length * 3}}
            """.trimIndent()

            writeDiag("Connecting to $SERVER_URL ...")
            val conn = (URL(SERVER_URL).openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                setRequestProperty("Content-Type", "application/json")
                connectTimeout = 2000
                readTimeout = TIMEOUT_MS
                doOutput = true
            }
            conn.outputStream.use { it.write(requestBody.toByteArray()) }

            val code = conn.responseCode
            writeDiag("HTTP response: $code")
            if (code != 200) {
                return rawText
            }

            val response = conn.inputStream.bufferedReader().readText()
            writeDiag("Response: ${response.take(200)}")
            val contentStart = response.indexOf("\"content\":\"") + 11
            val contentEnd = response.indexOf("\"", contentStart + 1)
            if (contentStart > 10 && contentEnd > contentStart) {
                val refined = response.substring(contentStart, contentEnd)
                    .replace("\\n", "\n")
                    .replace("\\\"", "\"")
                    .replace("\\\\", "\\")
                writeDiag("Refined: '${rawText.take(60)}' → '${refined.take(60)}'")
                return refined.ifBlank { rawText }
            }
            writeDiag("Failed to parse content from response")
        } catch (e: java.net.ConnectException) {
            writeDiag("ConnectException: ${e.message}")
        } catch (e: java.net.SocketTimeoutException) {
            writeDiag("SocketTimeoutException: ${e.message}")
        } catch (e: Exception) {
            writeDiag("Exception: ${e.javaClass.simpleName}: ${e.message}")
        }
        return rawText
    }

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

    private fun writeDiag(message: String) {
        try {
            val file = java.io.File(
                android.os.Environment.getExternalStoragePublicDirectory(
                    android.os.Environment.DIRECTORY_DOWNLOADS
                ),
                "nacre-llm-debug.txt"
            )
            val ts = java.text.SimpleDateFormat("HH:mm:ss.SSS", java.util.Locale.US).format(java.util.Date())
            file.appendText("[$ts] $message\n")
        } catch (_: Exception) {}
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
