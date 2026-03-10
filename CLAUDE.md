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

## Known Constraints
- AIDL Java stubs are hand-written (aidl compiler is x86-64 only)
- whisper.cpp / llama.cpp JNI bridges are in ime-ai/src/main/cpp/
- Physical keyboard detection uses InputDevice scanning
- Foldable detection: hingeAngle sensor + screen width >= 500dp → VSplit
