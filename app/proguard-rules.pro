# Nacre IME ProGuard Rules

# Keep InputMethodService
-keep class space.manus.nacre.ime.NacreInputMethodService { *; }

# Keep Compose
-dontwarn androidx.compose.**
