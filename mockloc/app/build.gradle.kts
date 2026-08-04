plugins {
    id("com.android.application")
}
android {
    namespace = "com.mockloc"
    compileSdk = 34
    defaultConfig {
        applicationId = "com.mockloc"
        minSdk = 28
        targetSdk = 34
    }
    buildTypes {
        release { isMinifyEnabled = false }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }
}
dependencies {
    // Same osmdroid version + cache-dir setup as the whyfi Mission view
    // (android/app/build.gradle.kts + mission/MissionScreen.kt). No Google
    // Maps — no API key, and whyfi deliberately avoids Play Services deps
    // (see AGENT.md). The tile cache points at context.cacheDir at runtime
    // to avoid needing a storage permission on minSdk 28.
    implementation("org.osmdroid:osmdroid-android:6.1.20")
}