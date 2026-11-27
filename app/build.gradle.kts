plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "com.musec.browser"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.musec.browser"
        minSdk = 26
        targetSdk = 35
        versionCode = 3
        versionName = "1.2.0"
    }

    // Build two separate lean APKs (~65-80 MB each instead of one ~500 MB universal APK):
    // 1. arm64-v8a -> Modern Android Phones (Pixel 10 Pro, Samsung, etc.) & 64-bit Android TVs
    // 2. armeabi-v7a -> 32-bit Android TVs (Chromecast with Google TV, older TV boxes)
    splits {
        abi {
            isEnable = true
            reset()
            include("arm64-v8a", "armeabi-v7a")
            isUniversalApk = false
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            signingConfig = signingConfigs.getByName("debug")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    packaging {
        jniLibs {
            // Compress native GeckoView .so libs inside APK (~2.5x smaller APK download)
            useLegacyPackaging = true
        }
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }
}

dependencies {
    // Official Mozilla GeckoView 144 (Full 16 KB page-size support for Google Pixel 10 Pro / Android 16!)
    implementation("org.mozilla.geckoview:geckoview:144.0.20251009125714")

    // AndroidX & Lifecycle (Aligned with GeckoView 144)
    implementation("androidx.core:core-ktx:1.17.0")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.9.3")
    implementation("androidx.lifecycle:lifecycle-process:2.9.3")
    implementation("androidx.activity:activity-compose:1.10.1")

    // Jetpack Compose Material 3 (Adaptive Phone Touch + Android TV D-Pad Focus UI)
    implementation(platform("androidx.compose:compose-bom:2025.05.00"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.material3:material3")

    // Coroutines
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.10.1")

    // Hardware-Backed Biometric Unlock for Encrypted Password Vault
    implementation("androidx.biometric:biometric:1.1.0")
}
