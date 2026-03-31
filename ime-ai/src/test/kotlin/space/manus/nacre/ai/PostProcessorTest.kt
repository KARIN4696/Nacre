package space.manus.nacre.ai

import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

class PostProcessorTest {

    private lateinit var processor: PostProcessor

    @Before
    fun setup() {
        processor = PostProcessor()
    }

    // --- Filler removal ---

    @Test
    fun `removeFiller strips Japanese fillers`() {
        assertEquals("今日は天気がいい", processor.removeFiller("えーと今日は天気がいい"))
        assertEquals("明日会議があります", processor.removeFiller("あのー明日会議があります"))
        assertEquals("そうですね", processor.removeFiller("うーんそうですね"))
        assertEquals("はい", processor.removeFiller("あーはい"))
    }

    @Test
    fun `removeFiller strips English fillers`() {
        assertEquals("I think so", processor.removeFiller("um I think so"))
        assertEquals("that's right", processor.removeFiller("uh that's right"))
        assertEquals("let me check", processor.removeFiller("you know let me check"))
    }

    @Test
    fun `removeFiller strips mid-sentence fillers`() {
        assertEquals("今日は天気がいいですね", processor.removeFiller("今日はえーと天気がいいですね"))
    }

    @Test
    fun `removeFiller preserves text without fillers`() {
        assertEquals("普通のテキスト", processor.removeFiller("普通のテキスト"))
    }

    // --- Self-correction detection ---

    @Test
    fun `resolveCorrections keeps final intent for janakute`() {
        assertEquals("水曜日に会議", processor.resolveCorrections("火曜日じゃなくて水曜日に会議"))
    }

    @Test
    fun `resolveCorrections keeps final intent for chigau`() {
        assertEquals("3時に集合", processor.resolveCorrections("2時に違う3時に集合"))
    }

    @Test
    fun `resolveCorrections handles English corrections`() {
        assertEquals("Wednesday", processor.resolveCorrections("Tuesday no wait Wednesday"))
        assertEquals("Wednesday", processor.resolveCorrections("Tuesday I mean Wednesday"))
    }

    @Test
    fun `resolveCorrections preserves text without corrections`() {
        assertEquals("普通の文章です", processor.resolveCorrections("普通の文章です"))
    }

    // --- Auto punctuation ---

    @Test
    fun `insertPunctuation adds period at sentence end`() {
        val result = processor.insertPunctuation("今日は天気がいいです")
        assertTrue(result.endsWith("。") || result.endsWith("です"))
    }

    @Test
    fun `insertPunctuation adds question mark for questions`() {
        val result = processor.insertPunctuation("今日は何曜日ですか")
        assertTrue(result.endsWith("？") || result.endsWith("か"))
    }

    // --- Voice command detection ---

    @Test
    fun `detectVoiceCommand recognizes newline`() {
        assertEquals(VoiceCommand.NewLine, processor.detectVoiceCommand("改行"))
        assertEquals(VoiceCommand.NewLine, processor.detectVoiceCommand("かいぎょう"))
    }

    @Test
    fun `detectVoiceCommand recognizes period`() {
        assertEquals(VoiceCommand.Period, processor.detectVoiceCommand("句点"))
        assertEquals(VoiceCommand.Period, processor.detectVoiceCommand("まる"))
    }

    @Test
    fun `detectVoiceCommand recognizes undo`() {
        assertEquals(VoiceCommand.Undo, processor.detectVoiceCommand("消して"))
        assertEquals(VoiceCommand.Undo, processor.detectVoiceCommand("取り消し"))
    }

    @Test
    fun `detectVoiceCommand recognizes commit`() {
        assertEquals(VoiceCommand.Commit, processor.detectVoiceCommand("確定"))
    }

    @Test
    fun `detectVoiceCommand returns null for normal text`() {
        assertNull(processor.detectVoiceCommand("今日は天気がいい"))
    }

    // --- Full pipeline ---

    @Test
    fun `process runs full pipeline`() {
        val result = processor.process("えーと今日は天気がいいです")
        assertFalse(result.text.contains("えーと"))
        assertNull(result.command)
    }

    @Test
    fun `process detects voice command`() {
        val result = processor.process("改行")
        assertEquals(VoiceCommand.NewLine, result.command)
    }
}
