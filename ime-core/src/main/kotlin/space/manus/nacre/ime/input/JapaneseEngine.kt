package space.manus.nacre.ime.input

/**
 * ローマ字→ひらがな変換エンジン。
 *
 * Phase 1: テーブルベースのローマ字→かな変換。
 * Mozc辞書によるかな→漢字変換は後続で統合予定。
 */
class JapaneseEngine {

    fun reset() {
        // Reserved for future stateful conversion
    }

    /**
     * ローマ字テキストをひらがなに変換する。
     * 未確定のローマ字はそのまま末尾に残す。
     * @param finalize true when committing — converts trailing 'n' to 'ん'
     */
    fun romajiToHiragana(input: String, finalize: Boolean = false): String {
        val result = StringBuilder()
        var i = 0
        val lower = input.lowercase()

        while (i < lower.length) {
            // Try 4-char match first, then 3, 2, 1 (supports xtsu/ltsu etc.)
            val matched = tryMatch(lower, i, 4)
                ?: tryMatch(lower, i, 3)
                ?: tryMatch(lower, i, 2)
                ?: tryMatch(lower, i, 1)

            if (matched != null) {
                result.append(matched.kana)
                i += matched.consumed
            } else {
                // Handle n' → ん (forced)
                if (lower[i] == 'n' && i + 1 < lower.length && lower[i + 1] == '\'') {
                    result.append("ん")
                    i += 2
                }
                // Handle "nn" → ん
                // If followed by vowel/y (e.g. "nno"→ん+の), consume only first n
                // If nn is at end or followed by consonant, consume both
                else if (i + 1 < lower.length && lower[i] == 'n' && lower[i + 1] == 'n') {
                    result.append("ん")
                    val afterNN = i + 2
                    if (afterNN < lower.length && lower[afterNN] in VOWELS_AND_Y) {
                        i += 1  // keep second n for next kana (e.g. nno → ん+の)
                    } else {
                        i += 2  // consume both (e.g. nn at end, or nnk...)
                    }
                }
                // Handle double consonant (kk, ss, tt, etc.) → っ
                else if (i + 1 < lower.length && lower[i] == lower[i + 1] &&
                    lower[i] in DOUBLE_CONSONANTS
                ) {
                    result.append("っ")
                    i += 1 // consume only the first consonant
                }
                // Standalone 'n' before non-vowel → ん
                // At end of string: only convert if finalizing (committing)
                else if (lower[i] == 'n' && (
                    (i + 1 < lower.length && lower[i + 1] !in VOWELS_AND_Y) ||
                    (i + 1 >= lower.length && finalize)
                )) {
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
        private val VOWELS_AND_Y = setOf('a', 'i', 'u', 'e', 'o', 'y')
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
            "ya" to "や", "yi" to "い", "yu" to "ゆ", "ye" to "いぇ", "yo" to "よ",
            // R-row
            "ra" to "ら", "ri" to "り", "ru" to "る", "re" to "れ", "ro" to "ろ",
            // W-row
            "wa" to "わ", "wi" to "うぃ", "wu" to "う", "we" to "うぇ", "wo" to "を", "wha" to "うぁ", "whi" to "うぃ", "whu" to "う", "whe" to "うぇ", "who" to "うぉ",
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
            "kya" to "きゃ", "kyi" to "きぃ", "kyu" to "きゅ", "kye" to "きぇ", "kyo" to "きょ",
            "sha" to "しゃ", "shu" to "しゅ", "she" to "しぇ", "sho" to "しょ",
            "sya" to "しゃ", "syi" to "しぃ", "syu" to "しゅ", "sye" to "しぇ", "syo" to "しょ",
            "cha" to "ちゃ", "chu" to "ちゅ", "che" to "ちぇ", "cho" to "ちょ",
            "tya" to "ちゃ", "tyi" to "ちぃ", "tyu" to "ちゅ", "tye" to "ちぇ", "tyo" to "ちょ",
            "nya" to "にゃ", "nyi" to "にぃ", "nyu" to "にゅ", "nye" to "にぇ", "nyo" to "にょ",
            "hya" to "ひゃ", "hyi" to "ひぃ", "hyu" to "ひゅ", "hye" to "ひぇ", "hyo" to "ひょ",
            "mya" to "みゃ", "myi" to "みぃ", "myu" to "みゅ", "mye" to "みぇ", "myo" to "みょ",
            "rya" to "りゃ", "ryi" to "りぃ", "ryu" to "りゅ", "rye" to "りぇ", "ryo" to "りょ",
            "gya" to "ぎゃ", "gyi" to "ぎぃ", "gyu" to "ぎゅ", "gye" to "ぎぇ", "gyo" to "ぎょ",
            "ja" to "じゃ", "ju" to "じゅ", "jo" to "じょ",
            "je" to "じぇ",
            "jya" to "じゃ", "jyi" to "じぃ", "jyu" to "じゅ", "jye" to "じぇ", "jyo" to "じょ",
            "bya" to "びゃ", "byi" to "びぃ", "byu" to "びゅ", "bye" to "びぇ", "byo" to "びょ",
            "pya" to "ぴゃ", "pyi" to "ぴぃ", "pyu" to "ぴゅ", "pye" to "ぴぇ", "pyo" to "ぴょ",
            // Foreign sounds (カタカナ語用)
            "fa" to "ふぁ", "fi" to "ふぃ", "fe" to "ふぇ", "fo" to "ふぉ",
            "thi" to "てぃ", "dhi" to "でぃ",
            "dhu" to "でゅ", "thu" to "てゅ",
            "tsa" to "つぁ", "tsi" to "つぃ", "tse" to "つぇ", "tso" to "つぉ",
            "va" to "ゔぁ", "vi" to "ゔぃ", "vu" to "ゔ", "ve" to "ゔぇ", "vo" to "ゔぉ",
            // Small kana
            "xa" to "ぁ", "xi" to "ぃ", "xu" to "ぅ", "xe" to "ぇ", "xo" to "ぉ",
            "xya" to "ゃ", "xyu" to "ゅ", "xyo" to "ょ",
            "xtu" to "っ", "xtsu" to "っ",
            // l* variants (same as x* — common alternative)
            "la" to "ぁ", "li" to "ぃ", "lu" to "ぅ", "le" to "ぇ", "lo" to "ぉ",
            "lya" to "ゃ", "lyu" to "ゅ", "lyo" to "ょ",
            "ltu" to "っ", "ltsu" to "っ",
            // Additional yōon variants
            "zya" to "じゃ", "zyi" to "じぃ", "zyu" to "じゅ", "zye" to "じぇ", "zyo" to "じょ",
            "dya" to "ぢゃ", "dyi" to "ぢぃ", "dyu" to "ぢゅ", "dye" to "ぢぇ", "dyo" to "ぢょ",
            // Punctuation
            "-" to "ー",
        )
    }
}
