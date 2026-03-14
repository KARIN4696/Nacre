# Nacre — Developer Keyboard for Android

## Architecture Decisions (変更時は必ず更新)

| 判断 | 理由 | 影響範囲 |
|------|------|----------|
| ComposeView は final | subclass不可 → addOnAttachStateChangeListener で ViewTree owners を stamp | NacreInputMethodService.kt |
| compileSdk = 34 | Termux aapt2 v2.19 が android-35 非対応 | 全 build.gradle.kts |
| core-ktx = 1.13.1 | 1.15.0 は compileSdk 35 必須 | ime-core/build.gradle.kts |
| VSplit 回転角 8° | 15° ではキーが画面外にクリップ | VSplitLayout.kt |
| Row 4 は 8キー偶数 | V-Split の size/2 分割で 4:4 均等にするため | KeymapConfig.kt, PresetProvider.kt |
| Shift キー明示配置 | swipe のみでは Shift+文字の同時操作不可 | 全レイアウト Row 4 |
| GL は右端寄り | Space 隣だと誤タップで IME 切替が頻発 | 全レイアウト Row 4 |
| キー高さ 56dp | 52dp では touch target が小さすぎ（Material 最小 48dp） | KeyboardScreen.kt, VSplitLayout.kt |
| CI で sed で aapt2 行削除 | gradle.properties の Termux パスが CI で存在しない | .github/workflows/build-android.yml |

## Module Structure

```
app/          — メインアプリ (設定画面, セットアップウィザード)
ime-core/     — IME サービス本体 (InputEngine, KeyboardScreen, TrackballView)
ime-config/   — キーマップ定義, テーマ, 設定永続化
ime-ai/       — AI アドオン (whisper.cpp JNI, llama.cpp JNI, AIDL)
```

## Key Layout (Base Layer Row 4)
`[Tab][Fn][Space(2x)][⇧] | gap/trackball | [BS(1.2x)][Enter(1.2x)][GL][.]`

## Build
- Termux: `./gradlew :app:assembleDebug`
- CI: GitHub Actions が gradle.properties から aapt2 行を sed で削除してビルド

## KenLM 5-gram 言語モデル（重要！）

- **訓練済みモデルあり**: GitHub Releases `v0.1.0-models` に `japanese-5gram.klm` (561MB)
- **訓練元**: Wikipedia日本語ダンプ → MeCab形態素解析 → KenLM 5-gram
- **訓練ワークフロー**: `.github/workflows/train-kenlm.yml` (手動トリガー)
- **ダウンロードURL**: `https://github.com/RYOITABASHI/Nacre/releases/download/v0.1.0-models/japanese-5gram.klm`
- **自動ロード**: `NacreInputMethodService.kt` がIME起動時に以下を検索:
  1. `filesDir/models/japanese-5gram.klm` (アプリ内部)
  2. `Downloads/kenlm-light/japanese-5gram.klm` (外部)
  3. `Downloads/japanese-5gram.klm` (外部)
- **ModelDownloader.kt**: `downloadKenLm()` でGitHub Releasesから自動ダウンロード
- **変換への効果**: Viterbi内のインクリメンタルスコアリング + 事後リスコアリングで変換精度大幅向上

## ローマ字変換ルール（JapaneseEngine.kt）

| 入力 | 出力 | ルール |
|------|------|--------|
| nn+母音 (nna,nni) | ん+な, ん+に | n1つ消費、2つ目のnが次の音の開始 |
| nn+子音 (nnk,nnd) | ん+k, ん+d | n2つ消費（2つ目のnは不要） |
| nn末尾 | ん | n2つ消費 |
| ny+母音 (nya,nyu) | にゃ, にゅ | テーブルマッチにフォールスルー |
| n+子音 (nk,nb) | ん+k, ん+b | n1つ消費、子音保持 |
| wo | を | 標準ローマ字（変換段階でうぉ/お読み替え候補生成） |

## 辞書・変換精度改善

- **静的バイグラム**: `dict/bigrams.tsv` — 頻出日本語コロケーション100+
- **かな読み替え**: `を→うぉ/お`, `ぢ↔じ`, `づ↔ず` のバリアント自動生成
- **辞書拡充**: `dict/slang_words.tsv` — テック用語、固有名詞、現代スラング
- **Viterbiビーム幅**: K=20(短)/18(中)/15(長)
- **完全一致ボーナス**: 8+文字→-7000, 7+→-6000, 5+→-5000

## Known Constraints
- AIDL Java stubs are hand-written (aidl compiler is x86-64 only)
- whisper.cpp / llama.cpp JNI bridges are in ime-ai/src/main/cpp/
- Physical keyboard detection uses InputDevice scanning
- Foldable detection: hingeAngle sensor + screen width >= 500dp → VSplit
