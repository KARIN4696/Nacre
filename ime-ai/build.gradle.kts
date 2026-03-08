plugins {
    id("com.android.library")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "space.manus.nacre.ai"
    compileSdk = 34

    defaultConfig {
        minSdk = 26

        ndk {
            abiFilters += listOf("arm64-v8a")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    // NDK/CMake for native JNI (whisper.cpp/llama.cpp)
    // Requires NDK with aarch64 host toolchain.
    // Uncomment when building on x86-64 host or CI:
    // ndkVersion = "27.0.12077973"
    // externalNativeBuild {
    //     cmake {
    //         path = file("src/main/cpp/CMakeLists.txt")
    //         version = "3.22.1"
    //     }
    // }
}

dependencies {
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")
}
