package space.manus.nacre.ime.input

/**
 * ローマ字→ひらがな変換エンジン。
 *
 * Phase 1: テーブルベースのローマ字→かな変換。
 * Mozc辞書によるかな→漢字変換は後続で統合予定。
 */
class JapaneseEngine {

    private var pending: String = ""

    fun reset() {
        pending = ""
    }

    /**
     * ローマ字テキストをひらがなに変換する。
     * 未確定のローマ字はそのまま末尾に残す。
     */
    fun romajiToHiragana(input: String): String {
        val result = StringBuilder()
        var i = 0
        val lower = input.lowercase()

        while (i < lower.length) {
            // Try 3-char match first, then 2-char, then 1-char
            val matched = tryMatch(lower, i, 3)
                ?: tryMatch(lower, i, 2)
                ?: tryMatch(lower, i, 1)

            if (matched != null) {
                result.append(matched.kana)
                i += matched.consumed
            } else {
                // Handle "nn" → ん
                if (i + 1 < lower.length && lower[i] == 'n' && lower[i + 1] == 'n') {
                    result.append("ん")
                    i += 2
                }
                // Handle double consonant (kk, ss, tt, etc.) → っ
                else if (i + 1 < lower.length && lower[i] == lower[i + 1] &&
                    lower[i] in DOUBLE_CONSONANTS
                ) {
                    result.append("っ")
                    i += 1 // consume only the first consonant
                }
                // Standalone 'n' before non-vowel or end
                else if (lower[i] == 'n' && (i + 1 >= lower.length || lower[i + 1] !in VOWELS_AND_Y)) {
                    result.append("ん")
                    i += 1
                }
                // Unknown char — keep as-is (pending romaji or punctuation)
                else {
                    result.append(lower[i])
                    i += 1
                }
            }
        }

        return result.toString()
    }

    private fun tryMatch(text: String, start: Int, length: Int): MatchResult? {
        if (start + length > text.length) return null
        val sub = text.substring(start, start + length)
        val kana = ROMAJI_TABLE[sub] ?: return null
        return MatchResult(kana, length)
    }

    private data class MatchResult(val kana: String, val consumed: Int)

    companion object {
        private val VOWELS_AND_Y = setOf('a', 'i', 'u', 'e', 'o', 'y', 'n')
        private val DOUBLE_CONSONANTS = setOf('k', 's', 't', 'p', 'c', 'g', 'z', 'd', 'b', 'j', 'f', 'h', 'r', 'w', 'm')

        private val ROMAJI_TABLE = mapOf(
            // Vowels
            "a" to "あ", "i" to "い", "u" to "う", "e" to "え", "o" to "お",
            // K-row
            "ka" to "か", "ki" to "き", "ku" to "く", "ke" to "け", "ko" to "こ",
            // S-row
            "sa" to "さ", "si" to "し", "su" to "す", "se" to "せ", "so" to "そ",
            "shi" to "し",
            // T-row
            "ta" to "た", "ti" to "ち", "tu" to "つ", "te" to "て", "to" to "と",
            "chi" to "ち", "tsu" to "つ",
            // N-row
            "na" to "な", "ni" to "に", "nu" to "ぬ", "ne" to "ね", "no" to "の",
            // H-row
            "ha" to "は", "hi" to "ひ", "hu" to "ふ", "he" to "へ", "ho" to "ほ",
            "fu" to "ふ",
            // M-row
            "ma" to "ま", "mi" to "み", "mu" to "む", "me" to "め", "mo" to "も",
            // Y-row
            "ya" to "や", "yu" to "ゆ", "yo" to "よ",
            // R-row
            "ra" to "ら", "ri" to "り", "ru" to "る", "re" to "れ", "ro" to "ろ",
            // W-row
            "wa" to "わ", "wi" to "ゐ", "we" to "ゑ", "wo" to "を",
            // G-row (dakuten)
            "ga" to "が", "gi" to "ぎ", "gu" to "ぐ", "ge" to "げ", "go" to "ご",
            // Z-row
            "za" to "ざ", "zi" to "じ", "zu" to "ず", "ze" to "ぜ", "zo" to "ぞ",
            "ji" to "じ",
            // D-row
            "da" to "だ", "di" to "ぢ", "du" to "づ", "de" to "で", "do" to "ど",
            // B-row
            "ba" to "ば", "bi" to "び", "bu" to "ぶ", "be" to "べ", "bo" to "ぼ",
            // P-row (handakuten)
            "pa" to "ぱ", "pi" to "ぴ", "pu" to "ぷ", "pe" to "ぺ", "po" to "ぽ",
            // Yōon (contracted sounds)
            "kya" to "きゃ", "kyu" to "きゅ", "kyo" to "きょ",
            "sha" to "しゃ", "shu" to "しゅ", "sho" to "しょ",
            "sya" to "しゃ", "syu" to "しゅ", "syo" to "しょ",
            "cha" to "ちゃ", "chu" to "ちゅ", "cho" to "ちょ",
            "tya" to "ちゃ", "tyu" to "ちゅ", "tyo" to "ちょ",
            "nya" to "にゃ", "nyu" to "にゅ", "nyo" to "にょ",
            "hya" to "ひゃ", "hyu" to "ひゅ", "hyo" to "ひょ",
            "mya" to "みゃ", "myu" to "みゅ", "myo" to "みょ",
            "rya" to "りゃ", "ryu" to "りゅ", "ryo" to "りょ",
            "gya" to "ぎゃ", "gyu" to "ぎゅ", "gyo" to "ぎょ",
            "ja" to "じゃ", "ju" to "じゅ", "jo" to "じょ",
            "jya" to "じゃ", "jyu" to "じゅ", "jyo" to "じょ",
            "bya" to "びゃ", "byu" to "びゅ", "byo" to "びょ",
            "pya" to "ぴゃ", "pyu" to "ぴゅ", "pyo" to "ぴょ",
            // Small kana
            "xa" to "ぁ", "xi" to "ぃ", "xu" to "ぅ", "xe" to "ぇ", "xo" to "ぉ",
            "xya" to "ゃ", "xyu" to "ゅ", "xyo" to "ょ",
            "xtu" to "っ", "xtsu" to "っ",
            // Punctuation
            "-" to "ー",
        )
    }
}
