# Nacre IME ProGuard Rules

# Keep all IME classes (service, input, keyboard, feedback, foldable)
-keep class space.manus.nacre.ime.** { *; }

# Keep Application class
-keep class space.manus.nacre.NacreApplication { *; }

# Keep Settings Activity
-keep class space.manus.nacre.ui.settings.** { *; }

# Keep AI Services (separate processes)
-keep class space.manus.nacre.ai.** { *; }

# Keep config classes
-keep class space.manus.nacre.config.** { *; }

# Keep Lifecycle/ViewModel/SavedState owners
-keep class * implements androidx.lifecycle.LifecycleOwner { *; }
-keep class * implements androidx.lifecycle.ViewModelStoreOwner { *; }
-keep class * implements androidx.savedstate.SavedStateRegistryOwner { *; }

# Compose — do not strip or rename
-keep class androidx.compose.** { *; }
-dontwarn androidx.compose.**

# Lifecycle
-keep class androidx.lifecycle.** { *; }

# Kotlin
-dontwarn kotlin.**
-keep class kotlin.Metadata { *; }
