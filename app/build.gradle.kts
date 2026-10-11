plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.plugin.compose")
    id("org.jetbrains.kotlin.plugin.serialization")

}
android {
    namespace = "com.okuyuki.quest"
    compileSdk = 36
    defaultConfig {
        applicationId = "com.okuyuki.quest"
        minSdk = 32
        targetSdk = 32
        versionCode = 1
        versionName = "0.1.0-alpha.1"
    }
    signingConfigs {
        val releasePath = System.getenv("ANDROID_KEYSTORE_PATH")
        if (!releasePath.isNullOrBlank()) create("release") {
            storeFile = file(releasePath)
            storePassword = System.getenv("ANDROID_KEYSTORE_PASSWORD")
            keyAlias = System.getenv("ANDROID_KEY_ALIAS")
            keyPassword = System.getenv("ANDROID_KEY_PASSWORD")
        }
    }
    buildTypes { release {
        isMinifyEnabled = false
        signingConfig = signingConfigs.findByName("release")
    } }
    buildFeatures { compose = true; buildConfig = true }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    packaging { resources.excludes += "/META-INF/{AL2.0,LGPL2.1}" }
}
dependencies {
    implementation(project(":core:protocol"))
    implementation(project(":core:stereo"))
    implementation(platform("androidx.compose:compose-bom:2026.06.00"))
    implementation("androidx.activity:activity-compose:1.11.0")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.9.4")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.9.4")
    implementation("androidx.core:core-ktx:1.17.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.10.2")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.9.0")
    testImplementation("junit:junit:4.13.2")

    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    compileOnly("com.meta.metavr:metavr-os-jsdk:207")
    testImplementation("com.squareup.okhttp3:mockwebserver:4.12.0")

}
