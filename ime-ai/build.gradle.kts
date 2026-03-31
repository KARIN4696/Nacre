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

    // NDK/CMake for native JNI — enabled on CI (x86-64 host), disabled on Termux (ARM host)
    // Termux cannot run NDK's x86-64 cmake/ninja binaries.
    // To enable locally: set ENABLE_NDK=true environment variable
    if (System.getenv("ENABLE_NDK") == "true" ||
        System.getenv("CI") == "true") {
        ndkVersion = "27.0.12077973"
        externalNativeBuild {
            cmake {
                path = file("src/main/cpp/CMakeLists.txt")
                version = "3.22.1"
            }
        }
        // When NDK builds libnacre-ai.so, exclude the prebuilt from jniLibs to avoid duplicate
        sourceSets["main"].jniLibs.setSrcDirs(emptyList<File>())
    }
}

dependencies {
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")
    testImplementation("junit:junit:4.13.2")
}
