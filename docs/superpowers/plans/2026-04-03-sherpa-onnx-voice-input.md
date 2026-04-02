# SenseVoice + LLM Post-Processing Voice Input

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Replace broken whisper.cpp JNI with sherpa-onnx SenseVoice for offline voice recognition, plus Qwen 2.5 LLM post-processing for Typeless-equivalent quality.

**Architecture:** sherpa-onnx AAR provides prebuilt native libs + Kotlin API. WhisperService internals are replaced but its AIDL interface (IWhisperService/IWhisperCallback) and process isolation (`:whisper`) are preserved. VoiceInputManager's callback structure stays unchanged. LLM post-processing uses existing llama-server (Qwen 2.5 3B) via HTTP, triggered after user taps stop.

**Tech Stack:** sherpa-onnx 1.12.34 (AAR), SenseVoice int8 model, Silero VAD, llama-server (Qwen 2.5 3B Q4_K_M), OkHttp for LLM HTTP calls.

---

## File Structure

### New Files
- `ime-ai/libs/sherpa-onnx-1.12.34.aar` — Prebuilt AAR (downloaded from GitHub Releases)
- `ime-ai/src/main/kotlin/space/manus/nacre/ai/SherpaRecognizer.kt` — Wrapper around sherpa-onnx OfflineRecognizer + Vad (~150 lines)
- `ime-ai/src/main/kotlin/space/manus/nacre/ai/LlmPostProcessor.kt` — HTTP client for llama-server voice text refinement (~100 lines)
- `ime-ai/src/test/kotlin/space/manus/nacre/ai/LlmPostProcessorTest.kt` — Unit tests

### Modified Files
- `ime-ai/build.gradle.kts` — Add sherpa-onnx AAR dependency, remove whisper.cpp NDK conditional
- `ime-ai/src/main/kotlin/space/manus/nacre/ai/WhisperService.kt` — Gut whisper.cpp internals, replace with SherpaRecognizer
- `ime-ai/src/main/kotlin/space/manus/nacre/ai/ModelDownloader.kt` — Update model URLs/filenames for SenseVoice + Silero VAD
- `ime-core/src/main/kotlin/space/manus/nacre/ime/input/VoiceInputManager.kt` — Add LLM post-processing step on stop
- `app/src/main/kotlin/space/manus/nacre/ui/settings/SettingsActivity.kt` — Update AI Models section for SenseVoice

### Removed Files (after migration verified)
- `ime-ai/src/main/cpp/nacre_whisper_jni.cpp` — No longer needed
- `ime-ai/src/main/kotlin/space/manus/nacre/ai/WhisperJni.kt` — No longer needed
- `ime-ai/src/main/jniLibs/arm64-v8a/libnacre-ai.so` — Replaced by sherpa-onnx AAR's SO
- `ime-ai/src/main/cpp/CMakeLists.txt` — whisper/llama/kenlm sections removed (kenlm kept if still used)

### Preserved Files (no changes)
- `ime-ai/src/main/java/space/manus/nacre/ai/IWhisperService.java` — AIDL interface unchanged
- `ime-ai/src/main/java/space/manus/nacre/ai/IWhisperCallback.java` — AIDL callback unchanged
- `ime-ai/src/main/kotlin/space/manus/nacre/ai/PostProcessor.kt` — Kept as fallback
- `ime-ai/src/test/kotlin/space/manus/nacre/ai/PostProcessorTest.kt` — 128 tests preserved
- `app/src/main/AndroidManifest.xml` — WhisperService declaration unchanged

---

## Task 1: Download sherpa-onnx AAR and SenseVoice model

**Files:**
- Create: `ime-ai/libs/sherpa-onnx-1.12.34.aar`
- Modify: `ime-ai/build.gradle.kts`

- [ ] **Step 1: Download sherpa-onnx AAR**

```bash
mkdir -p ~/Nacre/ime-ai/libs
curl -L -o ~/Nacre/ime-ai/libs/sherpa-onnx-1.12.34.aar \
  "https://github.com/k2-fsa/sherpa-onnx/releases/download/v1.12.34/sherpa-onnx-1.12.34.aar"
ls -lh ~/Nacre/ime-ai/libs/sherpa-onnx-1.12.34.aar
```
Expected: ~45MB AAR file.

- [ ] **Step 2: Download SenseVoice int8 model**

```bash
# Download and extract to a known location for later bundling/download
mkdir -p ~/Nacre/models-staging
curl -L "https://github.com/k2-fsa/sherpa-onnx/releases/download/asr-models/sherpa-onnx-sense-voice-zh-en-ja-ko-yue-2024-07-17.tar.bz2" \
  -o ~/Nacre/models-staging/sense-voice.tar.bz2
cd ~/Nacre/models-staging && tar xjf sense-voice.tar.bz2
ls -lh sherpa-onnx-sense-voice-zh-en-ja-ko-yue-2024-07-17/
```
Expected: `model.int8.onnx` (~155MB), `tokens.txt`, other files.

- [ ] **Step 3: Download Silero VAD model**

```bash
curl -L -o ~/Nacre/models-staging/silero_vad.onnx \
  "https://github.com/k2-fsa/sherpa-onnx/releases/download/asr-models/silero_vad.onnx"
ls -lh ~/Nacre/models-staging/silero_vad.onnx
```
Expected: ~1.6MB ONNX file.

- [ ] **Step 4: Update build.gradle.kts**

In `ime-ai/build.gradle.kts`, add the AAR dependency and remove the whisper.cpp NDK conditional build:

```kotlin
dependencies {
    implementation(files("libs/sherpa-onnx-1.12.34.aar"))
    // ... existing dependencies
}
```

Remove or comment out the `externalNativeBuild` block that references `CMakeLists.txt` for whisper.cpp. Keep KenLM if it has its own build path, otherwise handle separately.

- [ ] **Step 5: Verify build compiles**

```bash
cd ~/Nacre && ./gradlew :ime-ai:compileDebugKotlin 2>&1 | tail -10
```
Expected: BUILD SUCCESSFUL (sherpa-onnx classes available).

- [ ] **Step 6: Commit**

```bash
cd ~/Nacre && git add ime-ai/libs/ ime-ai/build.gradle.kts
git commit -m "deps: add sherpa-onnx AAR and download SenseVoice + Silero VAD models"
```

---

## Task 2: Create SherpaRecognizer wrapper

**Files:**
- Create: `ime-ai/src/main/kotlin/space/manus/nacre/ai/SherpaRecognizer.kt`

This file wraps sherpa-onnx's OfflineRecognizer + Vad into a simple API that WhisperService can call.

- [ ] **Step 1: Create SherpaRecognizer.kt**

```kotlin
package space.manus.nacre.ai

import android.content.Context
import android.util.Log
import com.k2fsa.sherpa.onnx.*

/**
 * Wrapper around sherpa-onnx OfflineRecognizer + Silero VAD.
 * Provides a simple API: feed audio samples, get transcribed text.
 */
class SherpaRecognizer(private val context: Context) {
    companion object {
        private const val TAG = "SherpaRecognizer"
        private const val SAMPLE_RATE = 16000
    }

    private var recognizer: OfflineRecognizer? = null
    private var vad: Vad? = null
    private var isInitialized = false

    /**
     * Initialize the recognizer with model files from the given directory.
     * @param modelDir Directory containing model.int8.onnx, tokens.txt
     * @param vadModelPath Path to silero_vad.onnx
     */
    fun initialize(modelDir: String, vadModelPath: String): Boolean {
        try {
            Log.i(TAG, "Initializing SherpaRecognizer from $modelDir")

            val config = OfflineRecognizerConfig(
                featConfig = FeatureConfig(sampleRate = SAMPLE_RATE, featureDim = 80),
                modelConfig = OfflineModelConfig(
                    senseVoice = OfflineSenseVoiceModelConfig(
                        model = "$modelDir/model.int8.onnx",
                        language = "ja",
                        useInverseTextNormalization = true,
                    ),
                    tokens = "$modelDir/tokens.txt",
                    numThreads = 2,
                    provider = "cpu",
                ),
            )
            recognizer = OfflineRecognizer(config = config)

            val vadConfig = VadModelConfig(
                sileroVadModelConfig = SileroVadModelConfig(
                    model = vadModelPath,
                    threshold = 0.5f,
                    minSilenceDuration = 0.3f,
                    minSpeechDuration = 0.25f,
                    windowSize = 512,
                    maxSpeechDuration = 15.0f,
                ),
                sampleRate = SAMPLE_RATE,
                numThreads = 1,
            )
            vad = Vad(config = vadConfig)

            isInitialized = true
            Log.i(TAG, "SherpaRecognizer initialized successfully")
            return true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to initialize SherpaRecognizer", e)
            isInitialized = false
            return false
        }
    }

    fun isReady(): Boolean = isInitialized

    /**
     * Feed audio samples to VAD. Returns list of transcribed segments.
     */
    fun processAudio(samples: FloatArray): List<String> {
        val rec = recognizer ?: return emptyList()
        val v = vad ?: return emptyList()

        v.acceptWaveform(samples)
        val results = mutableListOf<String>()

        while (!v.empty()) {
            val segment = v.front()
            val text = transcribeSegment(rec, segment.samples)
            if (text.isNotBlank()) {
                results.add(text)
            }
            v.pop()
        }
        return results
    }

    /**
     * Flush remaining audio in VAD buffer and transcribe.
     */
    fun flush(): List<String> {
        val rec = recognizer ?: return emptyList()
        val v = vad ?: return emptyList()

        v.flush()
        val results = mutableListOf<String>()
        while (!v.empty()) {
            val segment = v.front()
            val text = transcribeSegment(rec, segment.samples)
            if (text.isNotBlank()) {
                results.add(text)
            }
            v.pop()
        }
        return results
    }

    fun isSpeechDetected(): Boolean = vad?.isSpeechDetected() ?: false

    fun reset() {
        vad?.reset()
    }

    private fun transcribeSegment(rec: OfflineRecognizer, samples: FloatArray): String {
        val stream = rec.createStream()
        stream.acceptWaveform(samples, SAMPLE_RATE)
        rec.decode(stream)
        val result = rec.getResult(stream)
        stream.release()
        return result.text.trim()
    }

    fun release() {
        recognizer?.release()
        vad?.release()
        recognizer = null
        vad = null
        isInitialized = false
        Log.i(TAG, "SherpaRecognizer released")
    }
}
```

- [ ] **Step 2: Verify it compiles**

```bash
cd ~/Nacre && ./gradlew :ime-ai:compileDebugKotlin 2>&1 | tail -5
```
Expected: BUILD SUCCESSFUL. If sherpa-onnx API differs, adjust class/method names based on AAR contents.

- [ ] **Step 3: Commit**

```bash
cd ~/Nacre && git add ime-ai/src/main/kotlin/space/manus/nacre/ai/SherpaRecognizer.kt
git commit -m "feat: add SherpaRecognizer wrapper for sherpa-onnx SenseVoice + Silero VAD"
```

---

## Task 3: Replace WhisperService internals with SherpaRecognizer

**Files:**
- Modify: `ime-ai/src/main/kotlin/space/manus/nacre/ai/WhisperService.kt`

The AIDL interface stays the same. Replace all whisper.cpp JNI calls, custom VAD, audio preprocessing with SherpaRecognizer. The service name stays `WhisperService` to avoid breaking IPC bindings.

- [ ] **Step 1: Rewrite WhisperService.kt**

Key changes:
- Remove all `WhisperJni.*` calls
- Remove custom VAD (RMS/ZCR logic), audio preprocessing, hallucination detection
- Replace with `SherpaRecognizer.processAudio()` in recording loop
- Keep the foreground service notification
- Keep the AIDL binder implementation
- Keep `continuousCallback.onPartialResult()` and `onResult()` calls
- Remove all `writeDiag()` debug logging (no longer needed)

The recording loop becomes:
1. Record audio chunks (512 samples per read for VAD window size)
2. Feed to `sherpaRecognizer.processAudio()`
3. Any returned text → append to textBuffer → call `onPartialResult()`
4. On stop → `sherpaRecognizer.flush()` → final text → call `onResult()`

Full implementation in the step. The file should shrink from 801 lines to ~250 lines.

- [ ] **Step 2: Verify build**

```bash
cd ~/Nacre && ./gradlew :app:assembleDebug 2>&1 | tail -10
```

- [ ] **Step 3: Commit**

```bash
cd ~/Nacre && git add ime-ai/src/main/kotlin/space/manus/nacre/ai/WhisperService.kt
git commit -m "refactor: replace whisper.cpp with sherpa-onnx SenseVoice in WhisperService"
```

---

## Task 4: Update ModelDownloader for SenseVoice models

**Files:**
- Modify: `ime-ai/src/main/kotlin/space/manus/nacre/ai/ModelDownloader.kt`

- [ ] **Step 1: Update model constants and detection**

Change `WHISPER_FILENAME` to detect the SenseVoice model directory. The model consists of multiple files (model.int8.onnx, tokens.txt) in a directory, plus silero_vad.onnx separately.

Key changes:
- `WHISPER_FILENAME` → `SENSEVOICE_DIR = "sherpa-onnx-sense-voice-zh-en-ja-ko-yue-2024-07-17"`
- `getWhisperModelPath()` → `getSenseVoiceModelDir()` — returns dir path if model.int8.onnx exists in it
- `getVadModelPath()` — returns path to silero_vad.onnx
- Search in: internal storage `filesDir/models/`, then `/sdcard/Download/`
- Remove whisper download URL (no internet permission anyway)

- [ ] **Step 2: Verify build**

```bash
cd ~/Nacre && ./gradlew :app:assembleDebug 2>&1 | tail -5
```

- [ ] **Step 3: Commit**

```bash
cd ~/Nacre && git add ime-ai/src/main/kotlin/space/manus/nacre/ai/ModelDownloader.kt
git commit -m "refactor: update ModelDownloader for SenseVoice model directory structure"
```

---

## Task 5: Update VoiceInputManager for model loading + LLM post-processing

**Files:**
- Modify: `ime-core/src/main/kotlin/space/manus/nacre/ime/input/VoiceInputManager.kt`
- Create: `ime-ai/src/main/kotlin/space/manus/nacre/ai/LlmPostProcessor.kt`

- [ ] **Step 1: Create LlmPostProcessor.kt**

HTTP client that calls llama-server running on localhost:8080.

```kotlin
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
```

- [ ] **Step 2: Update VoiceInputManager — model loading**

In `onServiceConnected`, change model loading to use SenseVoice directory:
- Replace `downloader.getWhisperModelPath()` with `downloader.getSenseVoiceModelDir()`
- Pass model dir + VAD path to `svc.loadModel()` (encode both paths as `"$modelDir|$vadPath"`)
- Remove the copy-to-internal-storage logic (SherpaRecognizer handles this)
- Remove all `writeDiagnostic()` calls

- [ ] **Step 3: Update VoiceInputManager — LLM post-processing on stop**

In the `whisperCallback.onResult()` handler, add LLM post-processing:

```kotlin
override fun onResult(text: String) {
    android.os.Handler(android.os.Looper.getMainLooper()).post {
        isWhisperContinuousMode = false
        isListening = false
        partialText = ""
        rmsLevel = 0f
        releaseAudioFocus()

        if (text.isNotBlank()) {
            // Show loading state
            service.currentInputConnection?.setComposingText("整形中...", 1)

            // LLM post-processing in background
            Thread {
                val refined = LlmPostProcessor.refine(text)
                android.os.Handler(android.os.Looper.getMainLooper()).post {
                    service.currentInputConnection?.finishComposingText()
                    service.currentInputConnection?.commitText(refined, 1)
                }
            }.start()
        } else {
            service.currentInputConnection?.finishComposingText()
        }
    }
}
```

- [ ] **Step 4: Verify build**

```bash
cd ~/Nacre && ./gradlew :app:assembleDebug 2>&1 | tail -10
```

- [ ] **Step 5: Commit**

```bash
cd ~/Nacre && git add -A
git commit -m "feat: add LLM post-processing + update VoiceInputManager for SenseVoice"
```

---

## Task 6: Update Settings UI for SenseVoice

**Files:**
- Modify: `app/src/main/kotlin/space/manus/nacre/ui/settings/SettingsActivity.kt`

- [ ] **Step 1: Update WhisperModelSection**

Rename to `SenseVoiceModelSection`. Change:
- Title: "SenseVoice" instead of "Whisper Base"
- Detection: look for model directory instead of single file
- Description: "Offline voice input (Japanese/English/Chinese/Korean)"
- Import button: file picker for .tar.bz2 or pre-extracted directory

- [ ] **Step 2: Verify build and commit**

```bash
cd ~/Nacre && ./gradlew :app:assembleDebug 2>&1 | tail -5
git add app/src/main/kotlin/space/manus/nacre/ui/settings/SettingsActivity.kt
git commit -m "ui: update settings for SenseVoice model"
```

---

## Task 7: Copy models to device and end-to-end test

- [ ] **Step 1: Copy SenseVoice model to device internal storage**

```bash
# Copy model files to where the app can find them
cp -r ~/Nacre/models-staging/sherpa-onnx-sense-voice-zh-en-ja-ko-yue-2024-07-17 /sdcard/Download/
cp ~/Nacre/models-staging/silero_vad.onnx /sdcard/Download/
```

- [ ] **Step 2: Build and install APK**

```bash
cd ~/Nacre && ./gradlew :app:assembleDebug 2>&1 | tail -5
cp app/build/outputs/apk/debug/app-debug.apk /sdcard/Download/nacre-debug.apk
```

- [ ] **Step 3: Test voice input**

1. Install APK
2. Open Nacre settings → grant file access → verify SenseVoice shows "Ready"
3. Open any text field, switch to Nacre keyboard
4. Tap mic → speak Japanese → tap stop
5. Verify: "整形中..." appears briefly, then clean text is inserted

- [ ] **Step 4: Commit all remaining changes**

```bash
cd ~/Nacre && git add -A
git commit -m "feat: sherpa-onnx SenseVoice + LLM post-processing voice input"
```

---

## Task 8: Cleanup — remove whisper.cpp artifacts

**Files:**
- Remove: `ime-ai/src/main/cpp/nacre_whisper_jni.cpp`
- Remove: `ime-ai/src/main/kotlin/space/manus/nacre/ai/WhisperJni.kt`
- Remove: `ime-ai/src/main/jniLibs/arm64-v8a/libnacre-ai.so`
- Modify: `ime-ai/src/main/cpp/CMakeLists.txt` — remove whisper section
- Modify: `.github/workflows/build-android.yml` — remove whisper.cpp clone step

- [ ] **Step 1: Remove whisper.cpp files**

```bash
cd ~/Nacre
rm ime-ai/src/main/cpp/nacre_whisper_jni.cpp
rm ime-ai/src/main/kotlin/space/manus/nacre/ai/WhisperJni.kt
rm ime-ai/src/main/jniLibs/arm64-v8a/libnacre-ai.so
```

- [ ] **Step 2: Update CMakeLists.txt — remove whisper section**

Keep kenlm and llama sections if still used. Remove whisper source glob and library target.

- [ ] **Step 3: Update CI workflow — remove whisper.cpp clone**

Remove the "Clone whisper.cpp source" step from `.github/workflows/build-android.yml`.

- [ ] **Step 4: Verify build still works**

```bash
cd ~/Nacre && ./gradlew :app:assembleDebug 2>&1 | tail -5
```

- [ ] **Step 5: Commit**

```bash
cd ~/Nacre && git add -A
git commit -m "chore: remove whisper.cpp JNI artifacts, replaced by sherpa-onnx"
```

---

## Task 9: Push and CI build

- [ ] **Step 1: Push to GitHub**

```bash
cd ~/Nacre && git push origin main
```

- [ ] **Step 2: Monitor CI build**

```bash
cd ~/Nacre && gh run list --limit 1
gh run watch <run-id>
```

- [ ] **Step 3: Download and test CI APK**

```bash
gh run download <run-id> --dir $PREFIX/tmp/nacre-ci
cp $PREFIX/tmp/nacre-ci/nacre-debug/app-debug.apk /sdcard/Download/nacre-debug.apk
```

---

## Notes

- **Model distribution:** SenseVoice model (~155MB) + Silero VAD (~1.6MB) need to be placed in `/sdcard/Download/` by the user (downloaded from GitHub Releases). The app auto-detects via MANAGE_EXTERNAL_STORAGE.
- **LLM availability:** If llama-server is not running, LlmPostProcessor.refine() returns raw text (graceful fallback). PostProcessor's rule-based cleanup still applies.
- **Process isolation:** WhisperService stays in `:whisper` process. sherpa-onnx runs within that process (no additional JNI crash risk since it's a stable, well-tested library).
- **KenLM:** Not affected. KenLmJni/KenLmScorer/nacre_kenlm_jni.cpp remain for text conversion scoring.
- **LlamaJni/LlmService:** Not affected. These remain for in-app LLM transformations. The new LlmPostProcessor uses llama-server (separate process) via HTTP, not the AIDL LlmService.
