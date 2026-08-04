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
}