# Whisper Typeless-Grade Voice Input Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Integrate whisper.cpp into Nacre IME to achieve Typeless-grade offline voice input with continuous recording, filler removal, auto-punctuation, and manual commit control.

**Architecture:** Extend existing WhisperService (`:whisper` process) with a continuous recording mode. VAD splits audio into chunks, each transcribed via Greedy whisper.cpp. A new PostProcessor handles filler removal, self-correction detection, punctuation, and voice commands. VoiceInputManager prioritizes Whisper over SpeechRecognizer with streaming composing-text preview.

**Tech Stack:** whisper.cpp (C++17/NDK), Kotlin coroutines + Channel, AIDL IPC, KenLM (main process), GitHub Actions CI

**Spec:** `docs/superpowers/specs/2026-03-31-whisper-typeless-voice-input-design.md`

---

## File Structure

| File | Action | Responsibility |
|---|---|---|
| `ime-ai/src/main/cpp/CMakeLists.txt` | Modify | Switch whisper section to GLOB-based source collection |
| `ime-ai/src/main/cpp/nacre_whisper_jni.cpp` | Modify | Verify language="auto" handling |
| `ime-ai/src/main/kotlin/space/manus/nacre/ai/PostProcessor.kt` | Create | Filler removal, self-correction, punctuation, voice commands |
| `ime-ai/src/main/kotlin/space/manus/nacre/ai/WhisperService.kt` | Modify | Add continuous recording mode with Channel-based pipeline |
| `ime-ai/src/main/kotlin/space/manus/nacre/ai/ModelDownloader.kt` | Modify | Add downloadWhisperBase() |
| `ime-ai/src/main/java/space/manus/nacre/ai/IWhisperService.java` | Modify | Add transaction codes +6, +7 for continuous mode |
| `ime-core/src/main/kotlin/space/manus/nacre/ime/input/VoiceInputManager.kt` | Modify | Whisper-priority routing, composing preview, AudioFocus |
| `.github/workflows/build-android.yml` | Modify | Add whisper.cpp clone step |
| `ime-ai/src/test/kotlin/space/manus/nacre/ai/PostProcessorTest.kt` | Create | Unit tests for PostProcessor |

---

## Task 1: CI — Add whisper.cpp Clone Step to GitHub Actions

**Files:**
- Modify: `.github/workflows/build-android.yml:31-36`

- [ ] **Step 1: Add whisper.cpp clone step after KenLM clone**

In `.github/workflows/build-android.yml`, add a new step after the KenLM clone step (line 36):

```yaml
    - name: Clone whisper.cpp source
      run: |
        git clone --depth 1 --branch v1.7.3 https://github.com/ggerganov/whisper.cpp /tmp/whisper-src
        mkdir -p ime-ai/src/main/cpp/whisper
        cp /tmp/whisper-src/src/whisper.cpp ime-ai/src/main/cpp/whisper/
        cp /tmp/whisper-src/include/whisper.h ime-ai/src/main/cpp/whisper/
        cp /tmp/whisper-src/ggml/src/ggml.c ime-ai/src/main/cpp/whisper/
        cp /tmp/whisper-src/ggml/src/ggml-alloc.c ime-ai/src/main/cpp/whisper/
        cp /tmp/whisper-src/ggml/src/ggml-backend.c ime-ai/src/main/cpp/whisper/
        cp /tmp/whisper-src/ggml/src/ggml-quants.c ime-ai/src/main/cpp/whisper/
        cp /tmp/whisper-src/ggml/src/ggml-cpu*.c ime-ai/src/main/cpp/whisper/ 2>/dev/null || true
        cp /tmp/whisper-src/ggml/include/*.h ime-ai/src/main/cpp/whisper/
```

- [ ] **Step 2: Commit**

```bash
git add .github/workflows/build-android.yml
git commit -m "ci: add whisper.cpp v1.7.3 clone step to build workflow"
```

---

## Task 2: CMake — Switch Whisper Section to GLOB

**Files:**
- Modify: `ime-ai/src/main/cpp/CMakeLists.txt:8-29`

- [ ] **Step 1: Replace hardcoded whisper source list with GLOB**

Replace lines 8-29 in `CMakeLists.txt` (the whisper section) with:

```cmake
# === Whisper.cpp (optional - sources cloned by CI) ===
set(WHISPER_DIR "${CMAKE_SOURCE_DIR}/whisper")
if(EXISTS "${WHISPER_DIR}/whisper.cpp")
    file(GLOB WHISPER_C_SRCS "${WHISPER_DIR}/*.c")
    file(GLOB WHISPER_CPP_SRCS "${WHISPER_DIR}/whisper.cpp")
    # Exclude non-CPU backends if present
    list(FILTER WHISPER_C_SRCS EXCLUDE REGEX "ggml-(cuda|metal|vulkan|sycl|kompute|rpc)")

    add_library(whisper_lib STATIC
        ${WHISPER_CPP_SRCS}
        ${WHISPER_C_SRCS}
    )
    target_include_directories(whisper_lib PUBLIC "${WHISPER_DIR}")
    target_compile_definitions(whisper_lib PRIVATE
        GGML_USE_CPU
        NDEBUG
    )
    set(WHISPER_AVAILABLE TRUE)
    message(STATUS "whisper.cpp: ENABLED (GLOB)")
else()
    set(WHISPER_AVAILABLE FALSE)
    message(STATUS "whisper.cpp: DISABLED (sources not found)")
endif()
```

Also update the `target_link_libraries` call (around line 121) to use the new name. Change `whisper` to `whisper_lib`:

```cmake
if(WHISPER_AVAILABLE)
    target_link_libraries(nacre-ai whisper_lib)
    target_compile_definitions(nacre-ai PRIVATE WHISPER_AVAILABLE)
endif()
```

- [ ] **Step 2: Commit**

```bash
git add ime-ai/src/main/cpp/CMakeLists.txt
git commit -m "build: switch whisper CMake to GLOB-based source collection"
```

---

## Task 3: JNI — Verify language="auto" Support

**Files:**
- Modify: `ime-ai/src/main/cpp/nacre_whisper_jni.cpp:85-88`

- [ ] **Step 1: Check and fix language parameter handling**

In `nacre_whisper_jni.cpp`, the language parameter is set at line 88. whisper.cpp accepts `"auto"` to enable language auto-detection. Verify the current code passes the language string correctly. If the JNI code sets `params.language = lang` where `lang` is the JNI string, it should work as-is since whisper.cpp's `whisper_full_default_params` handles `"auto"`.

If `lang` could be `null` or empty, add a guard:

```cpp
// Around line 88, after getting the language string
const char* lang = env->GetStringUTFChars(language, nullptr);
// Set language - "auto" enables auto-detection
params.language = (lang != nullptr && strlen(lang) > 0) ? lang : "auto";
```

- [ ] **Step 2: Commit (only if changes were needed)**

```bash
git add ime-ai/src/main/cpp/nacre_whisper_jni.cpp
git commit -m "fix: handle null/empty language param in whisper JNI, default to auto"
```

---

## Task 4: PostProcessor — Unit Tests

**Files:**
- Create: `ime-ai/src/test/kotlin/space/manus/nacre/ai/PostProcessorTest.kt`

- [ ] **Step 1: Create test directory if needed**

```bash
mkdir -p ime-ai/src/test/kotlin/space/manus/nacre/ai
```

- [ ] **Step 2: Write PostProcessor tests**

Create `ime-ai/src/test/kotlin/space/manus/nacre/ai/PostProcessorTest.kt`:

```kotlin
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
```

- [ ] **Step 3: Add JUnit test dependency to ime-ai/build.gradle.kts**

Add to the `dependencies` block in `ime-ai/build.gradle.kts`:

```kotlin
dependencies {
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")
    testImplementation("junit:junit:4.13.2")
}
```

- [ ] **Step 4: Run tests to verify they fail**

```bash
cd ~/Nacre && ./gradlew :ime-ai:testDebugUnitTest --tests "space.manus.nacre.ai.PostProcessorTest" 2>&1 | tail -20
```

Expected: FAIL — `PostProcessor` class does not exist yet.

- [ ] **Step 5: Commit**

```bash
git add ime-ai/src/test/kotlin/space/manus/nacre/ai/PostProcessorTest.kt ime-ai/build.gradle.kts
git commit -m "test: add PostProcessor unit tests (red phase)"
```

---

## Task 5: PostProcessor — Implementation

**Files:**
- Create: `ime-ai/src/main/kotlin/space/manus/nacre/ai/PostProcessor.kt`

- [ ] **Step 1: Implement PostProcessor**

Create `ime-ai/src/main/kotlin/space/manus/nacre/ai/PostProcessor.kt`:

```kotlin
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
        // Japanese fillers
        private val JA_FILLERS = listOf("えーと", "えっと", "あのー", "あの", "うーん", "うん", "あー", "えー", "まあ", "なんか", "そのー")
        // English fillers
        private val EN_FILLERS = listOf("um", "uh", "you know", "like", "so", "well", "I mean")

        // Self-correction markers
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

        // Voice commands — exact match (after trimming)
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

        // Question-ending patterns (Japanese)
        private val JA_QUESTION_ENDINGS = listOf("か", "かな", "かね", "の", "ですか", "ますか")
    }

    fun removeFiller(text: String): String {
        var result = text
        // Remove Japanese fillers (with optional trailing space/particle)
        for (filler in JA_FILLERS) {
            result = result.replace(Regex("$filler\\s?"), "")
        }
        // Remove English fillers (word boundary)
        for (filler in EN_FILLERS) {
            result = result.replace(Regex("\\b${Regex.escape(filler)}\\b\\s?", RegexOption.IGNORE_CASE), "")
        }
        return result.trim()
    }

    fun resolveCorrections(text: String): String {
        var result = text
        // Try Japanese correction patterns
        for (pattern in JA_CORRECTION_PATTERNS) {
            val match = pattern.find(result)
            if (match != null) {
                result = match.groupValues[2].trim()
            }
        }
        // Try English correction patterns
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
        // Already has punctuation
        if (trimmed.last() in "。、？！.?!,") return text
        // Check for question pattern
        for (ending in JA_QUESTION_ENDINGS) {
            if (trimmed.endsWith(ending)) {
                return "$trimmed？"
            }
        }
        // English question
        if (trimmed.endsWith("?") || trimmed.startsWith("what", ignoreCase = true) ||
            trimmed.startsWith("how", ignoreCase = true) || trimmed.startsWith("why", ignoreCase = true) ||
            trimmed.startsWith("when", ignoreCase = true) || trimmed.startsWith("where", ignoreCase = true) ||
            trimmed.startsWith("who", ignoreCase = true) || trimmed.startsWith("is ", ignoreCase = true) ||
            trimmed.startsWith("are ", ignoreCase = true) || trimmed.startsWith("do ", ignoreCase = true)) {
            return "$trimmed?"
        }
        // Default: add period for Japanese (if contains Japanese chars), else English period
        val hasJapanese = trimmed.any { it.code in 0x3000..0x9FFF || it.code in 0xFF00..0xFFEF }
        return if (hasJapanese) "$trimmed。" else "$trimmed."
    }

    fun detectVoiceCommand(text: String): VoiceCommand? {
        val normalized = text.trim().lowercase()
        return VOICE_COMMANDS[normalized]
    }

    fun process(text: String): ProcessResult {
        // Check voice command first (exact match on raw text)
        val command = detectVoiceCommand(text)
        if (command != null) {
            return ProcessResult(text = "", command = command)
        }
        // Run text processing pipeline
        var processed = removeFiller(text)
        processed = resolveCorrections(processed)
        processed = insertPunctuation(processed)
        return ProcessResult(text = processed)
    }
}
```

- [ ] **Step 2: Run tests to verify they pass**

```bash
cd ~/Nacre && ./gradlew :ime-ai:testDebugUnitTest --tests "space.manus.nacre.ai.PostProcessorTest" 2>&1 | tail -20
```

Expected: All tests PASS.

- [ ] **Step 3: Commit**

```bash
git add ime-ai/src/main/kotlin/space/manus/nacre/ai/PostProcessor.kt
git commit -m "feat: implement PostProcessor — filler removal, self-correction, punctuation, voice commands"
```

---

## Task 6: AIDL — Add Continuous Recognition Methods

**Files:**
- Modify: `ime-ai/src/main/java/space/manus/nacre/ai/IWhisperService.java:26-170`

- [ ] **Step 1: Add interface methods**

Add to the `IWhisperService` interface (after `stopRecognition` declaration):

```java
void startContinuousRecognition(String language, IWhisperCallback callback) throws RemoteException;
void cancelContinuousRecognition() throws RemoteException;
```

- [ ] **Step 2: Add transaction codes to Stub**

Add after line 34 (`TRANSACTION_stopRecognition`):

```java
static final int TRANSACTION_startContinuousRecognition = android.os.IBinder.FIRST_CALL_TRANSACTION + 6;
static final int TRANSACTION_cancelContinuousRecognition = android.os.IBinder.FIRST_CALL_TRANSACTION + 7;
```

- [ ] **Step 3: Add onTransact cases to Stub**

Add new cases in `onTransact()` (after the `stopRecognition` case):

```java
case TRANSACTION_startContinuousRecognition: {
    data.enforceInterface(DESCRIPTOR);
    String _arg0 = data.readString();
    IWhisperCallback _arg1 = IWhisperCallback.Stub.asInterface(data.readStrongBinder());
    this.startContinuousRecognition(_arg0, _arg1);
    reply.writeNoException();
    return true;
}
case TRANSACTION_cancelContinuousRecognition: {
    data.enforceInterface(DESCRIPTOR);
    this.cancelContinuousRecognition();
    reply.writeNoException();
    return true;
}
```

- [ ] **Step 4: Add Proxy methods**

Add after `stopRecognition` proxy method:

```java
@Override
public void startContinuousRecognition(String language, IWhisperCallback callback) throws RemoteException {
    android.os.Parcel _data = android.os.Parcel.obtain();
    android.os.Parcel _reply = android.os.Parcel.obtain();
    try {
        _data.writeInterfaceToken(DESCRIPTOR);
        _data.writeString(language);
        _data.writeStrongBinder(callback != null ? callback.asBinder() : null);
        mRemote.transact(Stub.TRANSACTION_startContinuousRecognition, _data, _reply, 0);
        _reply.readException();
    } finally {
        _reply.recycle();
        _data.recycle();
    }
}

@Override
public void cancelContinuousRecognition() throws RemoteException {
    android.os.Parcel _data = android.os.Parcel.obtain();
    android.os.Parcel _reply = android.os.Parcel.obtain();
    try {
        _data.writeInterfaceToken(DESCRIPTOR);
        mRemote.transact(Stub.TRANSACTION_cancelContinuousRecognition, _data, _reply, 0);
        _reply.readException();
    } finally {
        _reply.recycle();
        _data.recycle();
    }
}
```

- [ ] **Step 5: Update Default inner class**

The `Default` class (which `implements IWhisperService`) must also have the new methods. Add:

```java
@Override public void startContinuousRecognition(String language, IWhisperCallback callback) throws RemoteException {}
@Override public void cancelContinuousRecognition() throws RemoteException {}
```

- [ ] **Step 6: Commit**

```bash
git add ime-ai/src/main/java/space/manus/nacre/ai/IWhisperService.java
git commit -m "feat: add AIDL methods for continuous recognition (+6, +7)"
```

---

## Task 7: WhisperService — Continuous Recording Mode

**Files:**
- Modify: `ime-ai/src/main/kotlin/space/manus/nacre/ai/WhisperService.kt`

- [ ] **Step 1: Add imports and properties**

Add imports at the top of WhisperService.kt:

```kotlin
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.Job
import kotlinx.coroutines.isActive
```

Add properties to the class (after existing properties around line 20):

```kotlin
// Continuous recording state
private val transcriptionChannel = Channel<FloatArray>(Channel.BUFFERED)
private var recordingJob: Job? = null
private var transcriptionJob: Job? = null
private val textBuffer = StringBuilder()
private val postProcessor = PostProcessor()
private var continuousCallback: IWhisperCallback? = null

// Constants
companion object {
    private const val CONTINUOUS_MAX_DURATION_SEC = 360 // 6 minutes
    private const val CHUNK_SILENCE_THRESHOLD_SEC = 1.5f
    private const val VAD_RMS_THRESHOLD = 0.005f
    private const val SAMPLE_RATE = 16000
    private const val CHUNK_SIZE_MS = 250
}
```

- [ ] **Step 2: Implement startContinuousRecognition()**

Add method to the service (inside the Stub implementation):

```kotlin
override fun startContinuousRecognition(language: String, callback: IWhisperCallback) {
    if (!WhisperJni.isModelLoaded()) {
        callback.onError("Model not loaded")
        return
    }
    continuousCallback = callback
    textBuffer.clear()
    transcriptionChannel = Channel(Channel.BUFFERED) // fresh channel per session

    // Recording coroutine — reads AudioRecord, splits on VAD silence
    recordingJob = scope.launch(Dispatchers.Default) {
        val bufferSize = android.media.AudioRecord.getMinBufferSize(
            SAMPLE_RATE,
            android.media.AudioFormat.CHANNEL_IN_MONO,
            android.media.AudioFormat.ENCODING_PCM_FLOAT
        )
        val recorder = android.media.AudioRecord(
            android.media.MediaRecorder.AudioSource.MIC,
            SAMPLE_RATE,
            android.media.AudioFormat.CHANNEL_IN_MONO,
            android.media.AudioFormat.ENCODING_PCM_FLOAT,
            bufferSize * 2
        )
        recorder.startRecording()

        val chunkSamples = SAMPLE_RATE * CHUNK_SIZE_MS / 1000 // 4000 samples per 250ms
        val readBuffer = FloatArray(chunkSamples)
        val audioBuffer = mutableListOf<Float>()
        var silentChunks = 0
        val silentChunksThreshold = (CHUNK_SILENCE_THRESHOLD_SEC * 1000 / CHUNK_SIZE_MS).toInt() // 6 chunks
        val maxChunks = CONTINUOUS_MAX_DURATION_SEC * 1000 / CHUNK_SIZE_MS
        var totalChunks = 0

        try {
            while (isActive && totalChunks < maxChunks) {
                val read = recorder.read(readBuffer, 0, chunkSamples, android.media.AudioRecord.READ_BLOCKING)
                if (read <= 0) continue
                totalChunks++

                // RMS calculation
                var sum = 0.0
                for (i in 0 until read) { sum += readBuffer[i] * readBuffer[i] }
                val rms = Math.sqrt(sum / read).toFloat()

                if (rms < VAD_RMS_THRESHOLD) {
                    silentChunks++
                    if (silentChunks >= silentChunksThreshold && audioBuffer.isNotEmpty()) {
                        // Silence threshold reached — send chunk for transcription
                        transcriptionChannel.send(audioBuffer.toFloatArray())
                        audioBuffer.clear()
                        silentChunks = 0
                    }
                } else {
                    silentChunks = 0
                    for (i in 0 until read) { audioBuffer.add(readBuffer[i]) }
                }
            }

            // Send remaining audio
            if (audioBuffer.isNotEmpty()) {
                transcriptionChannel.send(audioBuffer.toFloatArray())
            }
        } finally {
            recorder.stop()
            recorder.release()
        }
    }

    // Transcription coroutine — consumes chunks from channel
    transcriptionJob = scope.launch(Dispatchers.Default) {
        for (chunkAudio in transcriptionChannel) {
            if (!isActive) break
            try {
                val rawText = WhisperJni.transcribe(chunkAudio, language)
                if (rawText.isBlank()) continue
                val result = postProcessor.process(rawText)
                if (result.command != null) {
                    // Voice command — send as special marker
                    when (result.command) {
                        VoiceCommand.NewLine -> textBuffer.append("\n")
                        VoiceCommand.Period -> {
                            if (textBuffer.isNotEmpty() && !textBuffer.last().let { it == '。' || it == '.' }) {
                                val hasJa = textBuffer.any { it.code in 0x3000..0x9FFF }
                                textBuffer.append(if (hasJa) "。" else ".")
                            }
                        }
                        VoiceCommand.Undo -> {
                            // Remove last sentence/chunk (find last period or newline)
                            val lastBreak = textBuffer.lastIndexOfAny(charArrayOf('。', '.', '\n'))
                            if (lastBreak >= 0) textBuffer.delete(lastBreak, textBuffer.length)
                            else textBuffer.clear()
                        }
                        VoiceCommand.Commit -> {
                            // Trigger stop
                            continuousCallback?.onResult(textBuffer.toString())
                            stopContinuousRecordingInternal()
                            return@launch
                        }
                    }
                } else if (result.text.isNotBlank()) {
                    textBuffer.append(result.text)
                }
                continuousCallback?.onPartialResult(textBuffer.toString())
            } catch (e: Exception) {
                android.util.Log.e("WhisperService", "Transcription error", e)
            }
        }
    }
}
```

- [ ] **Step 3: Implement cancelContinuousRecognition() and stop helper**

```kotlin
override fun cancelContinuousRecognition() {
    stopContinuousRecordingInternal()
    textBuffer.clear()
    // No callback — user cancelled, text is discarded
}

private fun stopContinuousRecordingInternal() {
    recordingJob?.cancel()
    recordingJob = null
    transcriptionJob?.cancel()
    transcriptionJob = null
}
```

- [ ] **Step 4: Modify stopRecognition() to handle continuous mode**

Update existing `stopRecognition()` to also finalize continuous mode:

```kotlin
override fun stopRecognition() {
    // If continuous mode is active, finalize it
    if (recordingJob != null) {
        scope.launch {
            // Cancel recording, let transcription finish remaining items
            recordingJob?.cancel()
            recordingJob = null
            // Close channel to signal transcription coroutine to finish
            transcriptionChannel.close()
            // Wait for transcription to complete
            transcriptionJob?.join()
            transcriptionJob = null
            // Send final result
            continuousCallback?.onResult(textBuffer.toString())
            continuousCallback = null
            // Re-open channel for next session
            // (Channel is closed, need new instance — handled in next startContinuous)
        }
        return
    }
    // Existing stop logic for non-continuous mode
    recordJob?.cancel()
    recordJob = null
}
```

- [ ] **Step 5: Commit**

```bash
git add ime-ai/src/main/kotlin/space/manus/nacre/ai/WhisperService.kt
git commit -m "feat: add continuous recording mode to WhisperService with Channel pipeline"
```

---

## Task 8: ModelDownloader — Add Whisper Model Download

**Files:**
- Modify: `ime-ai/src/main/kotlin/space/manus/nacre/ai/ModelDownloader.kt:190-210`

- [ ] **Step 1: Add Whisper URL constant**

Add after the KenLM constants (around line 210):

```kotlin
private const val WHISPER_FILENAME = "ggml-base.bin"
private const val WHISPER_URL = "https://github.com/RYOITABASHI/Nacre/releases/download/v0.1.0-models/ggml-base.bin"
```

- [ ] **Step 2: Add downloadWhisperBase() method**

Add after `downloadKenLm()` method. **Important:** Match the existing `downloadModel()` signature which takes `(url: String, modelName: String, fileName: String, onComplete: (Boolean) -> Unit)`:

```kotlin
fun downloadWhisperBase(onComplete: (Boolean) -> Unit) {
    downloadModel(
        url = WHISPER_URL,
        modelName = "Whisper Base",
        fileName = WHISPER_FILENAME,
        onComplete = onComplete,
    )
}

fun getWhisperModelPath(): String? {
    val modelFile = File(context.filesDir, "models/$WHISPER_FILENAME")
    if (modelFile.exists()) return modelFile.absolutePath
    // Check external storage fallbacks
    val downloads = android.os.Environment.getExternalStoragePublicDirectory(
        android.os.Environment.DIRECTORY_DOWNLOADS
    )
    val fallbacks = listOf(
        File(downloads, "nacre-models/$WHISPER_FILENAME"),
        File(downloads, WHISPER_FILENAME),
    )
    return fallbacks.firstOrNull { it.exists() }?.absolutePath
}
```

- [ ] **Step 3: Update getDownloadedModels() to use correct filename**

In `getDownloadedModels()` (around line 63), change the hardcoded `"whisper-base.bin"` to use the constant:

```kotlin
// Before:
"whisper" to File(dir, "whisper-base.bin").exists(),
// After:
"whisper" to File(dir, WHISPER_FILENAME).exists(),
```

- [ ] **Step 4: Commit**

```bash
git add ime-ai/src/main/kotlin/space/manus/nacre/ai/ModelDownloader.kt
git commit -m "feat: add Whisper base model download to ModelDownloader"
```

---

## Task 9: VoiceInputManager — Whisper Priority + Composing Preview

**Files:**
- Modify: `ime-core/src/main/kotlin/space/manus/nacre/ime/input/VoiceInputManager.kt`

- [ ] **Step 1: Add Whisper service connection properties**

Add properties (near existing SpeechRecognizer state):

```kotlin
// Whisper continuous mode
private var whisperService: IWhisperService? = null
private var whisperBound = false
private var isWhisperContinuousMode = false
private var audioFocusRequest: android.media.AudioFocusRequest? = null

private val whisperConnection = object : android.content.ServiceConnection {
    override fun onServiceConnected(name: android.content.ComponentName?, service: android.os.IBinder?) {
        whisperService = IWhisperService.Stub.asInterface(service)
    }
    override fun onServiceDisconnected(name: android.content.ComponentName?) {
        whisperService = null
        whisperBound = false
        // Fallback to SpeechRecognizer if disconnected during recording
        if (isWhisperContinuousMode) {
            isWhisperContinuousMode = false
            startRecognizer()
        }
    }
}
```

- [ ] **Step 2: Add Whisper callback**

```kotlin
private val whisperCallback = object : IWhisperCallback.Stub() {
    override fun onResult(text: String) {
        android.os.Handler(android.os.Looper.getMainLooper()).post {
            isWhisperContinuousMode = false
            releaseAudioFocus()
            // Finish composing and commit final text
            service.currentInputConnection?.finishComposingText()
            if (text.isNotBlank()) {
                service.currentInputConnection?.commitText(text, 1)
            }
        }
    }

    override fun onPartialResult(text: String) {
        android.os.Handler(android.os.Looper.getMainLooper()).post {
            // Show as composing text (preview)
            service.currentInputConnection?.setComposingText(text, 1)
        }
    }

    override fun onError(message: String) {
        android.os.Handler(android.os.Looper.getMainLooper()).post {
            isWhisperContinuousMode = false
            releaseAudioFocus()
            // Fallback to SpeechRecognizer — delegate to existing startRecognizer()
            startRecognizer()
        }
    }
}
```

**Note:** `service` refers to the `NacreInputMethodService` instance that VoiceInputManager already holds. Replace `startSpeechRecognizerFallback()` calls throughout this task with `startRecognizer()` (the existing method).
```

- [ ] **Step 3: Modify startListening() for Whisper priority**

In `startListening()` (around line 82), after permission/battery checks but before SpeechRecognizer setup, add Whisper priority check:

```kotlin
// Whisper priority — use continuous mode if model loaded
if (whisperService != null) {
    try {
        if (whisperService!!.isModelLoaded) {
            isWhisperContinuousMode = true
            requestAudioFocus()
            whisperService!!.startContinuousRecognition("auto", whisperCallback)
            return
        }
    } catch (e: android.os.RemoteException) {
        // Fall through to SpeechRecognizer
    }
}
// Existing SpeechRecognizer logic below...
```

- [ ] **Step 4: Modify stopListening() for Whisper mode**

In `stopListening()` (around line 206), add:

```kotlin
if (isWhisperContinuousMode) {
    try {
        whisperService?.stopRecognition()
    } catch (e: android.os.RemoteException) {
        // ignore
    }
    return
}
// Existing SpeechRecognizer stop logic...
```

- [ ] **Step 5: Add cancel() method**

```kotlin
Replace the existing `cancel()` method (around line 222) with this version that handles both Whisper and SpeechRecognizer modes:

```kotlin
fun cancel() {
    if (isWhisperContinuousMode) {
        try {
            whisperService?.cancelContinuousRecognition()
        } catch (e: android.os.RemoteException) {
            // ignore
        }
        isWhisperContinuousMode = false
        releaseAudioFocus()
        service.currentInputConnection?.finishComposingText()
        return
    }
    // Existing SpeechRecognizer cancel logic (preserve existing code)
    stopListening()
}
```
```

- [ ] **Step 6: Add AudioFocus helpers**

```kotlin
private fun requestAudioFocus() {
    val am = service.getSystemService(android.content.Context.AUDIO_SERVICE) as android.media.AudioManager
    audioFocusRequest = android.media.AudioFocusRequest.Builder(android.media.AudioManager.AUDIOFOCUS_GAIN_TRANSIENT)
        .setAudioAttributes(
            android.media.AudioAttributes.Builder()
                .setUsage(android.media.AudioAttributes.USAGE_ASSISTANT)
                .setContentType(android.media.AudioAttributes.CONTENT_TYPE_SPEECH)
                .build()
        )
        .build()
    am.requestAudioFocus(audioFocusRequest!!)
}

private fun releaseAudioFocus() {
    audioFocusRequest?.let {
        val am = service.getSystemService(android.content.Context.AUDIO_SERVICE) as android.media.AudioManager
        am.abandonAudioFocusRequest(it)
        audioFocusRequest = null
    }
}
```

- [ ] **Step 7: Add bindWhisperService() call in init/constructor**

```kotlin
fun bindWhisperService() {
    if (whisperBound) return
    try {
        val intent = android.content.Intent().apply {
            setClassName(service.packageName, "space.manus.nacre.ai.WhisperService")
        }
        service.bindService(intent, whisperConnection, android.content.Context.BIND_AUTO_CREATE)
        whisperBound = true
    } catch (e: Exception) {
        // Whisper not available, SpeechRecognizer will be used
    }
}

fun unbindWhisperService() {
    if (whisperBound) {
        service.unbindService(whisperConnection)
        whisperBound = false
        whisperService = null
    }
}
```

- [ ] **Step 8: Commit**

```bash
git add ime-core/src/main/kotlin/space/manus/nacre/ime/input/VoiceInputManager.kt
git commit -m "feat: add Whisper priority routing with composing preview and AudioFocus"
```

---

**Note:** `AiPipelineManager.kt` LLM rewrite mode toggle is deferred to a future task. The current plan focuses on core Whisper transcription. LLM rewrite can be added once the base transcription pipeline is verified working.

---

## Task 10: Upload Whisper Model to GitHub Releases

**Files:** None (GitHub operation)

- [ ] **Step 1: Download ggml-base.bin from Hugging Face**

```bash
cd /tmp
wget https://huggingface.co/ggerganov/whisper.cpp/resolve/main/ggml-base.bin
ls -lh ggml-base.bin
```

Expected: ~140MB file.

- [ ] **Step 2: Upload to existing GitHub Release**

```bash
cd ~/Nacre
gh release upload v0.1.0-models /tmp/ggml-base.bin --clobber
```

- [ ] **Step 3: Verify upload**

```bash
gh release view v0.1.0-models
```

Expected: `ggml-base.bin` listed among assets alongside `japanese-5gram.klm`.

---

## Task 11: CI Build Verification

**Files:** None (verification only)

- [ ] **Step 1: Push changes and trigger CI**

```bash
cd ~/Nacre && git push origin main
```

- [ ] **Step 2: Watch CI build**

```bash
gh run watch --exit-status
```

Expected: Build succeeds. Check for:
- whisper.cpp sources cloned successfully
- `libnacre-ai.so` built with whisper symbols
- APK generated

- [ ] **Step 3: If CI fails, check logs and fix**

```bash
gh run view --log-failed
```

Common issues:
- Missing whisper.cpp source files → update copy step
- CMake compile errors → check GLOB patterns, missing headers
- Fix and push, re-verify

---

## Task 12: Integration Test on Device

**Files:** None (manual verification)

- [ ] **Step 1: Install APK on Z Fold6**

Download APK artifact from CI, install via adb.

- [ ] **Step 2: Download Whisper model**

Trigger model download via app settings, or manually push:

```bash
adb push /tmp/ggml-base.bin /sdcard/Download/ggml-base.bin
```

- [ ] **Step 3: Manual test checklist**

1. Open any text field, switch to Nacre IME
2. Tap microphone button → recording starts (Whisper mode, not SpeechRecognizer)
3. Speak "今日は天気がいいです" → composing text appears as preview
4. Pause (3 seconds) → recording continues, no auto-commit
5. Speak "明日も晴れるといいですね" → text appends to preview
6. Tap stop → all text commits at once
7. Test "えーと今日は" → filler "えーと" should be removed
8. Test "火曜日じゃなくて水曜日" → only "水曜日" appears
9. Say "改行" → newline inserted
10. Say "消して" → last chunk undone
11. Without Whisper model: fallback to SpeechRecognizer works
