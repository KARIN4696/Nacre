# Nacre IME: Conversion Quality, Voice Input, and NN Fix Design

**Date**: 2026-03-31
**Status**: Draft

## Problem Statement

Three issues need to be addressed:

1. **nn input regression**: Commit `606f070` reverted correct nn handling. `jyunni` produces `じゅんに` instead of `じゅんい` (順位).
2. **Conversion quality is far below Gboard**: Basic phrases like `おせわになっております` produce `御世話何っております` or `御世話仁菜っております` instead of `お世話になっております`. This is because Viterbi relies solely on dictionary cost + connection cost without language model context, producing grammatically impossible candidates.
3. **Voice input lacks Typeless-level features**: Punctuation detection is weak, no always-on dictation mode, no LLM post-processing for error correction.

### Root Cause Analysis (Conversion)

Screenshot evidence shows `おせわになっております` converting to:
- `御世話何っております` (1st candidate)
- `オセワニナッテオリマス` (2nd - katakana)
- `御世話仁菜っております` (3rd)
- `御世話仁奈っております` (4th)

Dictionary investigation reveals:
- `おせわ` → `御世話` (cost=3514) beats `お世話` (cost=3762)
- `になっ` → `担っ` (cost=2707) beats `になっ` (cost=4492)
- `にな` → `仁菜` (cost=5465), `仁奈` (cost=5503) exist as person names
- Viterbi splits `になって` as `にな|って` instead of `に|なって`

**Critical finding**: KenLM model is NOT bundled in the APK. It requires manual sideloading from Downloads/. Without KenLM, Viterbi has zero language model context and relies purely on dictionary cost + POS connection cost. This makes it impossible to distinguish natural Japanese from grammatically impossible sequences.

---

## Area 1: NN Input Fix

### Design

Revert to commit `9394b6a` behavior: `nn` always consumes both n's and produces `ん`.

**Change in `JapaneseEngine.kt` lines 37-47**:

```kotlin
// nn handling — "nn" always produces ん and consumes both n's.
// "nni" → ん+い, "nna" → ん+あ, "nnya" → ん+や
if (i + 1 < lower.length && lower[i + 1] == 'n') {
    result.append("ん")
    i += 2  // always consume both n's
    continue
}
```

**Behavior**:
- `junni` → `じゅんい` (順位)
- `konnnichiha` → `こんにちは` (user types 3 n's)
- `konnichiha` → `こんいちは` (accepted tradeoff; use `konnitiha` or `kon'nichiha`)

---

## Area 2: Conversion Quality Overhaul

### Strategy: KenLM must be always-available

The fundamental problem is that without a language model, no amount of dictionary cost tuning will produce Gboard-quality conversion. Gboard uses a neural language model that runs on-device.

### 2.1 Bundle a compact KenLM model in the APK

**Current state**: 561MB model in GitHub Releases, loaded from external storage.

**Solution**: Create a smaller KenLM model (3-gram or pruned 5-gram) that fits in the APK.

Target sizes:
- **3-gram pruned**: ~15-30MB (viable for APK assets)
- **5-gram aggressively pruned**: ~40-60MB (viable with on-demand decompression)

**Build pipeline**: Add a GitHub Actions workflow that:
1. Downloads Wikipedia/CC-100 Japanese corpus
2. Trains a pruned KenLM model with `--prune 0 0 1` (3-gram) or `--prune 0 1 2 3 4` (5-gram)
3. Quantizes to trie binary format (`-q 8 -b 8`)
4. Uploads as a build artifact / release asset
5. The compact model goes into `ime-core/src/main/assets/models/japanese-3gram.klm`

**Fallback**: If the full 5-gram model is sideloaded, use it instead. The bundled 3-gram is the floor.

### 2.2 Increase KenLM influence in Viterbi

Once KenLM is always available:

| Parameter | Current | New | Rationale |
|-----------|---------|-----|-----------|
| `VITERBI_LM_WEIGHT` | 1500 | 3000 | LM should strongly guide segmentation |
| `KENLM_WEIGHT` (rescore) | 2500 | 5000 | Post-hoc rescoring should dominate for long input |
| Beam width K (long) | 15-25 | 30-50 | Wider beam = more paths for LM to evaluate |

### 2.3 Post-rescore filtering

After `kenLmRescore()`, filter out candidates whose KenLM score is catastrophically bad:

```kotlin
// Remove candidates with LM score > 2x worse than best candidate
val bestLmCost = boosted.minOf { it.cost }
boosted.removeAll { it.cost > bestLmCost * 2.5 && it.cost > bestLmCost + 8000 }
```

### 2.4 Common phrase dictionary

Add a supplementary dictionary of common Japanese phrases that appear as single entries:

```
おせわになっております	お世話になっております	1842	1842	1500
おはようございます	おはようございます	1842	1842	1500
ありがとうございます	ありがとうございます	1842	1842	1500
よろしくおねがいします	よろしくお願いします	1842	1842	1500
おつかれさまです	お疲れ様です	1842	1842	1500
もうしわけございません	申し訳ございません	1842	1842	1500
```

These low-cost entries act as anchors: Viterbi will always find these full-phrase matches alongside segmented paths, and their cost is low enough to rank at or near the top even without KenLM.

File: `ime-core/src/main/assets/dict/common_phrases.tsv`

### 2.5 Person name dictionary enhancement

Current: 65,682 person name entries from Mozc OSS.

**Add**: mozcdic-ut-personal-names (84K modern names: celebrities, anime, VTubers). Apache 2.0 license.

- Download and convert to Nacre's TSV format (reading\tsurface\tleft_id\tright_id\tcost)
- Assign POS IDs 1921-1923
- Set cost to 6000-7000 (below general nouns but not competing with common words)
- File: `ime-core/src/main/assets/dict/person_names.tsv`
- Load via existing `loadSupplementaryDict()` mechanism

### 2.6 Dictionary cost rebalancing for common patterns

The Mozc dictionary has some counterintuitive cost rankings:
- `御世話` (3514) < `お世話` (3762) — but `お世話` is far more common in modern usage
- `担っ` (2707) < `になっ` (4492) — but `になっ` is grammatically fundamental

**Solution**: Rather than editing the Mozc dictionary directly, apply cost adjustments for hiragana function words in Viterbi. When a hiragana segment matches as both a function word (particle/aux verb) and a content word, boost the function word interpretation:

```kotlin
// In Viterbi: boost hiragana entries that match as function words
if (entry.surface == segment && segment.all { it in '\u3040'..'\u309F' }) {
    if (isFunctionWord(entry.leftGroup) || isParticle(entry.leftGroup)) {
        cost -= 2000  // Strongly prefer function word reading for hiragana
    }
}
```

This prevents `になっ` from being read as `担っ` when the input is hiragana.

---

## Area 3: Voice Input Enhancement

### 3.1 Always-on dictation mode

Extend the current `continuousMode` to true always-on:

```kotlin
// In recognition listener:
// On ERROR_NO_MATCH (silence): immediately restart, no delay
// On ERROR_SPEECH_TIMEOUT: restart with short delay (100ms)
// On ERROR_RECOGNIZER_BUSY: queue restart for 200ms
// Only stop on: explicit user action, battery critical, ERROR_NETWORK (no fallback)
```

**Max silence timeout**: Set `EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS` to 10000ms (max practical value). Combined with zero-gap restart on timeout, this creates effectively unlimited listening.

**Battery guard**: Stop after 15 minutes of continuous no-speech detection. Show subtle indicator "Listening paused — tap to resume".

### 3.2 Enhanced punctuation detection

Expand `smartPunctuation` patterns from ~20 to 80+:

**New question patterns**:
```
ですよね, でしょ, じゃん, じゃないですか, ってこと, と思いませんか,
知ってる, わかる, ある, いる (at end after content),
どうして, なぜ, いくら, いくつ, どのくらい (at start)
```

**New exclamation patterns**:
```
すげー, やべー, まじ, マジ, やった, できた, 終わった,
頑張れ, 気をつけて, いいね, 最高, ありがとう
```

**Mid-sentence punctuation insertion**:
After recognizer returns a full result, scan for natural clause boundaries and insert `、`:

```kotlin
// Pattern: [content word][conjunctive particle][content word]
// e.g. "今日は天気がいいのでピクニックに行きました"
// → "今日は天気がいいので、ピクニックに行きました"
val clauseBreaks = listOf("ので", "から", "けど", "けれど", "が", "し", "たら", "ても", "のに", "ながら")
```

### 3.3 LLM post-processing pipeline

Add an optional LLM cleanup pass after voice recognition commits text:

```
Raw recognition → convertVoiceCommands → smartPunctuation → [LLM cleanup] → commit
```

**LLM cleanup responsibilities**:
1. Fix obvious misrecognitions (e.g. homophone errors)
2. Add/adjust punctuation for natural reading
3. Correct grammar/word boundaries
4. (Future) Summarize/rephrase

**Implementation**: Use existing `LlmReranker` infrastructure in `ime-ai` module. The LLM processes each committed utterance asynchronously:

```kotlin
// After committing text:
if (llmCleanupEnabled && llmReranker.isAvailable()) {
    val cleaned = llmReranker.cleanupUtterance(committedText, committedInSession.toString())
    if (cleaned != committedText) {
        // Replace the last committed text with cleaned version
        replaceLastCommitted(committedText, cleaned)
    }
}
```

**User sees**: Raw text appears immediately, then smoothly transitions to cleaned text (like Typeless behavior).

### 3.4 Future: Whisper on-device (out of scope)

Replace Android SpeechRecognizer with whisper.cpp for:
- Better accuracy (especially for technical terms)
- True offline support
- VAD-based continuous listening with lower battery impact

This is a large separate project. The current improvements use Android's built-in SpeechRecognizer.

---

## Implementation Phases

### Phase 1: NN fix + Conversion foundations (this session)
- Revert nn handling to `9394b6a` behavior
- Add common phrases dictionary
- Add hiragana function word boost in Viterbi
- Increase KenLM weights (for users who have it)

### Phase 2: Bundled KenLM model (next session)
- Create compact 3-gram KenLM training workflow
- Bundle model in APK assets (~20-30MB)
- Auto-load bundled model as fallback
- Add post-rescore filtering

### Phase 3: Person names + Voice input (next session)
- Integrate mozcdic-ut-personal-names
- Always-on dictation mode
- Enhanced punctuation patterns (80+)
- Mid-sentence punctuation insertion

### Phase 4: LLM voice cleanup (future)
- LLM post-processing pipeline for voice
- Real-time text replacement UX
- User preference for cleanup aggressiveness

---

## Files Modified

| File | Change |
|------|--------|
| `ime-core/.../JapaneseEngine.kt` | NN handling revert |
| `ime-core/.../NacreDictionary.kt` | KenLM weights, function word boost, post-rescore filter, common phrase loading |
| `ime-core/.../VoiceInputManager.kt` | Always-on mode, enhanced punctuation, LLM pipeline |
| `ime-core/.../NacreInputMethodService.kt` | Bundled KenLM loading, common phrase dict loading |
| `ime-core/src/main/assets/dict/common_phrases.tsv` | NEW: Common Japanese phrases |
| `ime-core/src/main/assets/dict/person_names.tsv` | NEW: Modern person names (Phase 3) |
| `ime-core/src/main/assets/models/japanese-3gram.klm` | NEW: Bundled KenLM model (Phase 2) |
| `.github/workflows/train-kenlm-compact.yml` | NEW: Compact KenLM training (Phase 2) |
