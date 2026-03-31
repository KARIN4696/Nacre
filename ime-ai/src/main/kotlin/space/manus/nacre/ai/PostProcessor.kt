package space.manus.nacre.ai

sealed class VoiceCommand {
    object NewLine : VoiceCommand()
    object Period : VoiceCommand()
    object Undo : VoiceCommand()
    object Commit : VoiceCommand()
}

data class ProcessResult(
    val text: String,
    val command: VoiceCommand? = null
)

class PostProcessor {

    companion object {
        private val JA_FILLERS = listOf("えーと", "えっと", "あのー", "あの", "うーん", "うん", "あー", "えー", "まあ", "なんか", "そのー")
        private val EN_FILLERS = listOf("um", "uh", "you know", "like", "well", "I mean")

        private val JA_CORRECTION_PATTERNS = listOf(
            Regex("(.*)じゃなくて(.+)"),
            Regex("(.*)ではなくて?(.+)"),
            Regex("(.*)違う(.+)"),
            Regex("(.*)じゃなく(.+)"),
        )
        private val EN_CORRECTION_PATTERNS = listOf(
            Regex("(.*)\\bno wait\\b(.+)", RegexOption.IGNORE_CASE),
            Regex("(.*)\\bI mean\\b(.+)", RegexOption.IGNORE_CASE),
            Regex("(.*)\\bactually\\b(.+)", RegexOption.IGNORE_CASE),
            Regex("(.*)\\bsorry\\b,?(.+)", RegexOption.IGNORE_CASE),
        )

        private val VOICE_COMMANDS = mapOf(
            "改行" to VoiceCommand.NewLine,
            "かいぎょう" to VoiceCommand.NewLine,
            "new line" to VoiceCommand.NewLine,
            "句点" to VoiceCommand.Period,
            "まる" to VoiceCommand.Period,
            "period" to VoiceCommand.Period,
            "消して" to VoiceCommand.Undo,
            "取り消し" to VoiceCommand.Undo,
            "undo" to VoiceCommand.Undo,
            "確定" to VoiceCommand.Commit,
            "かくてい" to VoiceCommand.Commit,
            "done" to VoiceCommand.Commit,
        )

        private val JA_QUESTION_ENDINGS = listOf("か", "かな", "かね", "の", "ですか", "ますか")
    }

    fun removeFiller(text: String): String {
        var result = text
        for (filler in JA_FILLERS) {
            result = result.replace(Regex("$filler\\s?"), "")
        }
        for (filler in EN_FILLERS) {
            result = result.replace(Regex("\\b${Regex.escape(filler)}\\b\\s?", RegexOption.IGNORE_CASE), "")
        }
        return result.trim()
    }

    fun resolveCorrections(text: String): String {
        var result = text
        for (pattern in JA_CORRECTION_PATTERNS) {
            val match = pattern.find(result)
            if (match != null) {
                result = match.groupValues[2].trim()
            }
        }
        for (pattern in EN_CORRECTION_PATTERNS) {
            val match = pattern.find(result)
            if (match != null) {
                result = match.groupValues[2].trim()
            }
        }
        return result
    }

    fun insertPunctuation(text: String): String {
        if (text.isBlank()) return text
        val trimmed = text.trimEnd()
        if (trimmed.last() in "。、？！.?!,") return text
        for (ending in JA_QUESTION_ENDINGS) {
            if (trimmed.endsWith(ending)) {
                return "$trimmed？"
            }
        }
        if (trimmed.startsWith("what", ignoreCase = true) ||
            trimmed.startsWith("how", ignoreCase = true) || trimmed.startsWith("why", ignoreCase = true) ||
            trimmed.startsWith("when", ignoreCase = true) || trimmed.startsWith("where", ignoreCase = true) ||
            trimmed.startsWith("who", ignoreCase = true) || trimmed.startsWith("is ", ignoreCase = true) ||
            trimmed.startsWith("are ", ignoreCase = true) || trimmed.startsWith("do ", ignoreCase = true)) {
            return "$trimmed?"
        }
        val hasJapanese = trimmed.any { it.code in 0x3000..0x9FFF || it.code in 0xFF00..0xFFEF }
        return if (hasJapanese) "$trimmed。" else "$trimmed."
    }

    fun detectVoiceCommand(text: String): VoiceCommand? {
        val normalized = text.trim().lowercase()
        return VOICE_COMMANDS[normalized]
    }

    fun process(text: String): ProcessResult {
        val command = detectVoiceCommand(text)
        if (command != null) {
            return ProcessResult(text = "", command = command)
        }
        var processed = removeFiller(text)
        processed = resolveCorrections(processed)
        processed = insertPunctuation(processed)
        return ProcessResult(text = processed)
    }
}
