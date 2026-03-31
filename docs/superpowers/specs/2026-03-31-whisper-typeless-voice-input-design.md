# Whisper統合 — Typeless級音声入力設計書

**Date:** 2026-03-31
**Status:** Draft
**Module:** ime-ai, ime-core

## 背景

Android SpeechRecognizerは無音で勝手に確定するため、考え中のポーズで文が途切れる。deferred commit方式はSpeechRecognizerの制約で失敗した。whisper.cppを統合し、自前でAudioRecordを制御することで確定タイミングを完全にコントロールする。

Typeless（$30/月のAI音声入力アプリ）と同等以上の機能を、完全オフライン・無料・IME統合で実現する。

## 設計判断

| 判断 | 選択 | 理由 |
|---|---|---|
| whisper.cppソース配置 | CIでclone（KenLMと同パターン） | 統一性、リポジトリ肥大化回避 |
| 推論戦略 | Greedy + KenLMリランキング | beam searchは最終出力1つ。Greedy高速+KenLMで文脈担保 |
| モデル配布 | GitHub Releases（KenLMと同じ） | 既存インフラ再利用 |
| アーキテクチャ | 既存WhisperService改修 | 80%コード再利用、AIDL/プロセス分離そのまま |

## Typeless機能対応表

| Typeless機能 | Nacre実装 | 差別化 |
|---|---|---|
| 6分連続録音 | AudioRecord常時録音 | 同等 |
| フィラー除去 | PostProcessorルールベース | 同等 |
| 自己訂正検出 | PostProcessorパターンマッチ | 同等 |
| 自動句読点 | KenLM + ルールベース | 同等 |
| プレビュー→確定 | composing text + 確定UI | 同等 |
| LLMリライト | LlmService（llama.cpp） | **オフライン** |
| 日英混在 | Whisper language=auto | 同等 |
| 音声コマンド | VoiceInputManagerで検出 | 同等 |
| 適応学習 | KenLMユーザー辞書 | 将来拡張 |

**Typelessを超える点:**
- 完全オフライン — サーバー不要、プライバシー完全保証
- IME統合 — キーボード切替不要、同じIME内で音声⇔タイプ切替
- KenLMリランキング — 日本語変換精度の底上げ
- 無料 — Typelessは$30/月

## 全体アーキテクチャ

```
┌──────────────────────────────────────────────────────────┐
│ VoiceInputManager (ime-core)                              │
│  - Whisper優先、SpeechRecognizerフォールバック             │
│  - composing textでリアルタイムプレビュー                  │
│  - 停止タップ → プレビュー表示 → 確定 or 編集             │
│  - 音声コマンド: 「改行」「句点」「消して」               │
├──────────────────────────────────────────────────────────┤
│ AIDL IPC (IWhisperService / IWhisperCallback)             │
├──────────────────────────────────────────────────────────┤
│ WhisperService (:whisper process)                         │
│  - 常時録音モード（AudioRecord 16kHz PCM、最大6分）       │
│  - VADチャンク分割（無音1.5秒でチャンク境界）             │
│  - チャンクごとにGreedy推論（4 threads）                  │
│  - PostProcessorでフィラー除去・句読点・訂正検出          │
│  - バッファ蓄積 → onPartialResult逐次送信                │
│  - stopRecognition()でバッファ全体を返す                  │
├──────────────────────────────────────────────────────────┤
│ PostProcessor (ime-ai, same process as WhisperService)    │
│  - KenLMリランキング（チャンク接続の自然さ）              │
│  - 自動句読点挿入（。、？！）                             │
│  - フィラー/冗長表現除去                                  │
│  - 言い直し検出 & 最終意図のみ保持                        │
│  - 日英混在: そのまま保持（Whisperが自動判定）            │
├──────────────────────────────────────────────────────────┤
│ LlmService (:llm process) — オプション高品質モード        │
│  - 口語→書き言葉リライト（Typelessの核心機能）            │
│  - トーン適応（カジュアル/フォーマル）                    │
│  - 要約・整形                                             │
├──────────────────────────────────────────────────────────┤
│ WhisperJni → libnacre-ai.so (whisper.cpp + kenlm)        │
└──────────────────────────────────────────────────────────┘
```

## データフロー

```
[タップ開始]
    │
    ▼
AudioRecord (16kHz mono PCM float32)
    │
    ▼ 250ms間隔でRMS計算
VAD (Voice Activity Detection)
    │
    ├─ 音声検出中 → PCMバッファに蓄積
    │
    ├─ 無音1.5秒検出 → チャンク境界確定
    │       │
    │       ▼
    │   WhisperJni.transcribe(chunk)  ← Greedy, 4threads
    │       │
    │       ▼
    │   PostProcessor.process(rawText)
    │       ├─ フィラー除去: えーと, あのー, うーん, あー, um, uh
    │       ├─ 自己訂正検出: 「…じゃなくて」「…違う」→後半のみ保持
    │       ├─ 自動句読点: 文末イントネーション + KenLMスコア
    │       └─ 音声コマンド検出: 「改行」「消して」「確定」
    │       │
    │       ▼
    │   チャンクバッファに追加
    │   onPartialResult(accumulated) → VoiceInputManager
    │       │
    │       ▼
    │   composing textとしてプレビュー表示（未確定）
    │
    ├─ 黙っている → 何も起きない、録音継続
    │
    └─ [タップ停止]
            │
            ▼
        最後のチャンク推論（残りバッファ）
            │
            ▼
        KenLMで全文スコアリング（チャンク境界の自然さ確認）
            │
            ▼
        onResult(finalText) → VoiceInputManager
            │
            ├─ LLMリライトOFF → そのまま確定
            └─ LLMリライトON → LlmService.transform()
                    │
                    ▼
                口語→書き言葉変換、整形
                    │
                    ▼
                確定（commitText）
```

## 変更ファイル一覧

| ファイル | 変更内容 |
|---|---|
| `ime-ai/src/main/cpp/CMakeLists.txt` | whisper.cppソースパス追加（既存の条件付きビルドを維持） |
| `ime-ai/src/main/cpp/nacre_whisper_jni.cpp` | 変更なし（Greedy維持、既存で十分） |
| `ime-ai/src/main/kotlin/.../WhisperService.kt` | `startContinuousRecognition()` 追加、VADチャンク分割改修、バッファ蓄積、PostProcessor呼び出し |
| `ime-ai/src/main/kotlin/.../PostProcessor.kt` | **新規** — フィラー除去、自己訂正検出、自動句読点、音声コマンド検出 |
| `ime-ai/src/main/kotlin/.../ModelDownloader.kt` | `downloadWhisperBase()` 追加 |
| `ime-ai/src/main/java/.../IWhisperService.java` | `startContinuousRecognition()` メソッド追加 |
| `ime-ai/src/main/java/.../IWhisperCallback.java` | 変更なし（onPartialResult既存） |
| `ime-core/.../VoiceInputManager.kt` | Whisper優先ロジック、composingプレビュー、確定/停止制御 |
| `ime-core/.../AiPipelineManager.kt` | LLMリライトモード切替追加 |
| `.github/workflows/build-android.yml` | whisper.cpp clone + copy ステップ追加 |

## コンポーネント詳細

### PostProcessor（新規）

```kotlin
// ime-ai/src/main/kotlin/space/manus/nacre/ai/PostProcessor.kt

class PostProcessor {
    fun removeFiller(text: String): String
    fun resolveCorrections(text: String): String
    fun insertPunctuation(text: String, kenLmScorer: KenLmScorer?): String
    fun detectVoiceCommand(text: String): VoiceCommand?
    fun process(text: String, kenLmScorer: KenLmScorer?): ProcessResult
}

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
```

### WhisperService 常時録音モード

既存の `startRecognition()` はそのまま残し、`startContinuousRecognition()` を追加:

- 既存: 最大30秒、無音5秒で自動停止 → `onResult`
- 新規: 最大6分、無音で停止しない、VADチャンク分割で逐次推論 → `onPartialResult` 連続送信、`stopRecognition()` で `onResult`

### VoiceInputManager 改修

```
WhisperService利用可能？
  ├─ YES → startContinuousRecognition()
  │         composing textでプレビュー
  │         停止タップ → commitText
  └─ NO  → 既存SpeechRecognizer（フォールバック）
```

### IWhisperService AIDL追加

```java
// 既存メソッドに追加
void startContinuousRecognition(String language, IWhisperCallback callback);
```

Transaction code追加、Stub/Proxyに実装追加。

### CI変更（build-android.yml）

KenLMと同パターンでwhisper.cppをclone:

```yaml
- name: Clone whisper.cpp source
  run: |
    git clone --depth 1 https://github.com/ggerganov/whisper.cpp /tmp/whisper-src
    mkdir -p ime-ai/src/main/cpp/whisper
    cp /tmp/whisper-src/src/whisper.cpp ime-ai/src/main/cpp/whisper/
    cp /tmp/whisper-src/include/whisper.h ime-ai/src/main/cpp/whisper/
    cp -r /tmp/whisper-src/ggml/src/* ime-ai/src/main/cpp/whisper/
    cp -r /tmp/whisper-src/ggml/include/* ime-ai/src/main/cpp/whisper/
```

### ModelDownloader 追加

```kotlin
fun downloadWhisperBase(onProgress: (Float) -> Unit, onComplete: (Boolean) -> Unit) {
    // URL: GitHub Releases v0.1.0-models/ggml-base.bin (~140MB)
    // Destination: context.filesDir/models/ggml-base.bin
    // Resume support（既存のダウンロードロジック再利用）
}
```

## エラーハンドリング

| シナリオ | 対処 |
|---|---|
| Whisperモデル未ダウンロード | SpeechRecognizerにフォールバック。設定画面でダウンロード促進 |
| 推論中にOOM | `:whisper`プロセスクラッシュ → IME本体は無事。フォールバック |
| AudioRecord取得失敗 | 権限チェック。エラーコールバックでUI通知 |
| 6分上限到達 | 自動停止 → 最後のチャンク推論 → onResult。ユーザー通知 |
| バッテリー低下（20%未満） | 既存のバッテリーチェック維持。音声入力無効化 |
| WhisperService接続切れ | onServiceDisconnected → 再接続 or フォールバック |
| チャンク推論遅延（>1秒） | 録音継続（推論は別コルーチン）。プレビューが少し遅れるだけ |

## パフォーマンス目安（Z Fold6, Snapdragon 8 Gen 3）

| 処理 | 所要時間 |
|---|---|
| チャンク推論（1-3秒音声） | ~200-400ms |
| PostProcessor | ~10ms |
| KenLM全文スコアリング | ~5ms |
| LLMリライト（オプション） | ~1-3秒 |

## テスト戦略

**ユニットテスト（JVM）:**
- PostProcessor: フィラー除去、自己訂正検出、句読点挿入、音声コマンド検出のパターン網羅
- 日本語/英語/日英混在の各パターン

**インストルメンテーションテスト（実機）:**
- WhisperJni: モデルロード → transcribe → 結果検証
- WhisperService: AIDL経由の常時録音モード → 停止 → 結果取得
- VoiceInputManager: Whisper優先選択、フォールバック動作

**手動テスト（Z Fold6実機）:**
- 長時間録音（3分+）の安定性
- 考え中の沈黙 → 再開の自然さ
- フィラー除去の精度
- 日英混在の認識精度
- LLMリライトの品質
