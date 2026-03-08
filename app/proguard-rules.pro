# Nacre IME ProGuard Rules

# Keep InputMethodService
-keep class space.manus.nacre.ime.NacreInputMethodService { *; }

# Keep AI Services (separate processes)
-keep class space.manus.nacre.ai.WhisperService { *; }
-keep class space.manus.nacre.ai.LlmService { *; }

# Keep DictionaryProvider interface
-keep interface space.manus.nacre.ime.input.DictionaryProvider { *; }
-keep class space.manus.nacre.ime.input.NacreDictionary { *; }

# Keep config classes (used in JSON serialization)
-keep class space.manus.nacre.config.** { *; }

# Keep macro/snippet data classes (JSON persistence)
-keep class space.manus.nacre.ime.input.MacroEngine$Macro { *; }
-keep class space.manus.nacre.ime.input.MacroEngine$MacroStep { *; }
-keep class space.manus.nacre.ime.input.SnippetEngine$Snippet { *; }
-keep class space.manus.nacre.ime.input.AutoConvertEngine$ConvertRule { *; }

# Compose
-dontwarn androidx.compose.**

# Kotlin
-dontwarn kotlin.**
-keep class kotlin.Metadata { *; }
