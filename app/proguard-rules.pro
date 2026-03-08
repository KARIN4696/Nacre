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

# Compose (BOM already bundles its own rules, just suppress warnings)
-dontwarn androidx.compose.**

# Kotlin
-dontwarn kotlin.**
-keep class kotlin.Metadata { *; }
