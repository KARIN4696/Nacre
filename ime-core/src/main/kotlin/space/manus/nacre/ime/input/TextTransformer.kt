package space.manus.nacre.ime.input

/**
 * Rule-based text transformation engine. Fully offline, no model required.
 */
object TextTransformer {

    data class TransformCommand(
        val id: String,
        val label: String,
        val description: String,
    )

    val commands = listOf(
        TransformCommand("keigo", "敬語にして", "Polite Japanese"),
        TransformCommand("katakana", "カタカナにして", "To katakana"),
        TransformCommand("hiragana", "ひらがなにして", "To hiragana"),
        TransformCommand("uppercase", "大文字にして", "UPPERCASE"),
        TransformCommand("lowercase", "小文字にして", "lowercase"),
        TransformCommand("fullwidth", "全角にして", "Full-width"),
        TransformCommand("halfwidth", "半角にして", "Half-width"),
        TransformCommand("reverse", "逆にして", "Reverse"),
        TransformCommand("titlecase", "タイトルケース", "Title Case"),
    )

    fun transform(text: String, commandId: String): String {
        if (text.isBlank()) return text
        return when (commandId) {
            "keigo" -> toKeigo(text)
            "katakana" -> hiraganaToKatakana(text)
            "hiragana" -> katakanaToHiragana(text)
            "uppercase" -> text.uppercase()
            "lowercase" -> text.lowercase()
            "fullwidth" -> toFullWidth(text)
            "halfwidth" -> toHalfWidth(text)
            "reverse" -> text.reversed()
            "titlecase" -> text.split(" ").joinToString(" ") { word ->
                word.replaceFirstChar { it.uppercase() }
            }
            else -> text
        }
    }

    private fun toKeigo(text: String): String {
        var result = text
        val conversions = listOf(
            "だ。" to "です。",
            "だよ" to "ですよ",
            "だね" to "ですね",
            "だった" to "でした",
            "じゃない" to "ではありません",
            "じゃなかった" to "ではありませんでした",
            "する" to "いたします",
            "した" to "いたしました",
            "できる" to "できます",
            "できない" to "できません",
            "ある" to "あります",
            "ない" to "ありません",
            "いる" to "おります",
            "いた" to "おりました",
            "言う" to "申します",
            "言った" to "申しました",
            "見る" to "拝見します",
            "見た" to "拝見しました",
            "行く" to "参ります",
            "行った" to "参りました",
            "来る" to "いらっしゃいます",
            "来た" to "いらっしゃいました",
            "食べる" to "いただきます",
            "食べた" to "いただきました",
            "もらう" to "いただきます",
            "もらった" to "いただきました",
            "知る" to "存じます",
            "知った" to "存じました",
            "知らない" to "存じません",
            "わかる" to "わかります",
            "わかった" to "わかりました",
            "思う" to "存じます",
            "思った" to "存じました",
            "聞く" to "伺います",
            "聞いた" to "伺いました",
            "会う" to "お会いします",
            "会った" to "お会いしました",
            "待つ" to "お待ちします",
            "待った" to "お待ちしました",
            "読む" to "拝読します",
            "読んだ" to "拝読しました",
            "書く" to "お書きします",
            "書いた" to "お書きしました",
            "送る" to "お送りします",
            "送った" to "お送りしました",
            "よ。" to "。",
            "ね。" to "。",
            "よね。" to "。",
            "んだ" to "のです",
            "けど" to "ですが",
        )
        for ((from, to) in conversions.sortedByDescending { it.first.length }) {
            result = result.replace(from, to)
        }
        return result
    }

    private fun hiraganaToKatakana(text: String): String {
        val sb = StringBuilder(text.length)
        for (ch in text) {
            if (ch in '\u3041'..'\u3096') {
                sb.append((ch.code + 0x60).toChar())
            } else {
                sb.append(ch)
            }
        }
        return sb.toString()
    }

    private fun katakanaToHiragana(text: String): String {
        val sb = StringBuilder(text.length)
        for (ch in text) {
            if (ch in '\u30A1'..'\u30F6') {
                sb.append((ch.code - 0x60).toChar())
            } else {
                sb.append(ch)
            }
        }
        return sb.toString()
    }

    private fun toFullWidth(text: String): String {
        val sb = StringBuilder(text.length)
        for (ch in text) {
            when {
                ch == ' ' -> sb.append('\u3000')
                ch.code in 0x21..0x7E -> sb.append((ch.code + 0xFEE0).toChar())
                else -> sb.append(ch)
            }
        }
        return sb.toString()
    }

    private fun toHalfWidth(text: String): String {
        val sb = StringBuilder(text.length)
        for (ch in text) {
            when {
                ch == '\u3000' -> sb.append(' ')
                ch.code in 0xFF01..0xFF5E -> sb.append((ch.code - 0xFEE0).toChar())
                else -> sb.append(ch)
            }
        }
        return sb.toString()
    }
}
