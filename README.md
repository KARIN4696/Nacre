# Nacre

Developer-focused split keyboard IME for Android.

## Features

- **Split layout** with adjustable angle for ergonomic thumb typing
- **Japanese romaji input** with real-time kana-kanji conversion
- **Mozc dictionary** for accurate Japanese conversion with Viterbi algorithm
- **Voice input** with continuous recognition and streaming partial commit
- **AI-powered prediction** via local LLM candidate reranking (localhost only)
- **English autocomplete** with frequency-based prediction
- **Developer shortcuts** including Ctrl+key combos, tab key, escape, and macro triggers
- **Emoji picker** and symbol candidate bar with alternatives
- **Password field protection** disables prediction, history, and voice input

## Architecture

| Module | Description |
|---|---|
| `app` | Main application, settings UI, billing |
| `ime-core` | InputMethodService, keyboard layout, input engine, dictionary, voice input |
| `ime-config` | Key layout definitions and configuration |
| `ime-ai` | AI services (whisper.cpp, llama.cpp, KenLM) running in isolated process |

## Build

```bash
./gradlew :app:assembleDebug
```

Requires Android SDK with compileSdk 34.

## Security

- **Offline by default**: The base IME has no INTERNET permission. All input processing happens on-device.
- **Localhost-only LLM**: The optional LLM reranker is restricted to loopback addresses (127.0.0.1, localhost, ::1) to prevent keystroke exfiltration.
- **Password field protection**: Prediction, candidate history, voice input, and AI features are automatically disabled in password fields.
- **No input logging**: User keystrokes, voice transcriptions, and LLM prompts are never written to logcat, even in debug builds.
- **Process isolation**: AI services (llama.cpp, whisper.cpp) run in a separate process (`:llm`) for crash isolation.

## License

Copyright 2026 RYO ITABASHI. Licensed under the [Apache License 2.0](LICENSE).

Third-party components are listed in [THIRD_PARTY_LICENSES.md](THIRD_PARTY_LICENSES.md).
