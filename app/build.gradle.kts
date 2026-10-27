plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.translate.livecaption"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.translate.livecaption"
        minSdk = 29
        targetSdk = 35
        versionCode = 1
        versionName = "0.1.0"

        ndk {
            // arm64 覆盖绝大多数真机；x86_64 用于模拟器
            abiFilters += listOf("arm64-v8a", "x86_64")
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        viewBinding = false
    }

    packaging {
        jniLibs {
            useLegacyPackaging = false
        }
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.16.0")
    implementation("androidx.appcompat:appcompat:1.7.1")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")

    // 用于解开 tar.bz2 分发的模型包（X-ASR 模型只提供 tar.bz2）
    implementation("org.apache.commons:commons-compress:1.26.2")

    // 本地 AAR：sherpa-onnx 1.13.8（含 arm64-v8a / x86_64 的 jni）
    implementation(files("libs/sherpa-onnx.aar"))
}
