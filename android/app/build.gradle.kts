plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("com.google.devtools.ksp")
}

android {
    namespace = "com.whyfi.app"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.whyfi.app"
        // 28 (Android 9) is also where the WiFi scan-throttle behavior this
        // app works around was introduced — see scan/ScanThrottleController.kt.
        minSdk = 28
        targetSdk = 34
        versionCode = (System.getenv("WHYFI_VERSION_CODE") ?: "1").toInt()
        versionName = System.getenv("WHYFI_VERSION_NAME") ?: "0.1.0"

        // Pre-fills (but doesn't lock) the backend URL field in Settings on
        // first launch — set via WHYFI_PUBLIC_URL in .env, same value as
        // DJANGO_CSRF_TRUSTED_ORIGINS. Deliberately URL only, never a token:
        // a sensor token baked into a shared APK would be the same token
        // for everyone who downloads it, defeating per-device tokens
        // entirely — see MEMORY.md.
        buildConfigField("String", "DEFAULT_BACKEND_URL", "\"${System.getenv("WHYFI_PUBLIC_URL") ?: ""}\"")
    }

    // Signing key must persist across builds and be kept outside git — see
    // MEMORY.md. android-builder always sets ANDROID_KEYSTORE_PATH, but the
    // file itself only exists once someone has generated a release
    // keystore — check for the file, not just the env var, so a first-time
    // build without one yet still produces a usable unsigned APK instead
    // of failing.
    val keystoreFile = System.getenv("ANDROID_KEYSTORE_PATH")?.let { file(it) }?.takeIf { it.exists() }

    signingConfigs {
        create("release") {
            if (keystoreFile != null) {
                storeFile = keystoreFile
                storePassword = System.getenv("ANDROID_KEYSTORE_PASSWORD")
                keyAlias = System.getenv("ANDROID_KEY_ALIAS")
                keyPassword = System.getenv("ANDROID_KEY_PASSWORD")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            if (keystoreFile != null) {
                signingConfig = signingConfigs.getByName("release")
            }
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
    composeOptions {
        kotlinCompilerExtensionVersion = "1.5.14"
    }
    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }
}

dependencies {
    implementation(platform("androidx.compose:compose-bom:2024.06.00"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.activity:activity-compose:1.9.1")
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.4")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.4")

    implementation("androidx.room:room-runtime:2.6.1")
    implementation("androidx.room:room-ktx:2.6.1")
    ksp("androidx.room:room-compiler:2.6.1")

    implementation("androidx.work:work-runtime-ktx:2.9.1")

    implementation("com.squareup.retrofit2:retrofit:2.11.0")
    implementation("com.squareup.retrofit2:converter-gson:2.11.0")

    // Precision ranging enhancement tier — see ble/UwbLocateManager.kt for
    // the cross-vendor caveat (only works with Android-compatible ranging
    // profiles, not Apple AirTags). Pinned to 1.0.0-alpha07 rather than the
    // newer 1.1.0-alpha01, which requires compileSdk 36 + AGP 8.9+ — not
    // worth the rest of the toolchain churn for a tier that's already a
    // documented stub (see UwbLocateManager.isPeerRangingProfileSupported).
    implementation("androidx.core.uwb:uwb:1.0.0-alpha07")

    testImplementation("junit:junit:4.13.2")
}
