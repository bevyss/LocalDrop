plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "org.localdrop.app"
    compileSdk = 36

    defaultConfig {
        applicationId = "org.localdrop.app"
        minSdk = 26
        targetSdk = 36
        versionCode = 1
        versionName = "0.1.0"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    buildFeatures { compose = true }
    sourceSets.getByName("test").resources.srcDir("../../../protocol/test-vectors")
    sourceSets.getByName("main").assets.srcDir("../../../localization")
}

kotlin {
    compilerOptions { jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17) }
}

dependencies {
    val composeBom = platform("androidx.compose:compose-bom:2025.12.00")
    implementation(composeBom)
    implementation("androidx.compose.material3:material3")
    implementation("androidx.activity:activity-compose:1.12.4")
    implementation("com.google.code.gson:gson:2.14.0")
    implementation("androidx.core:core:1.16.0")
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.json:json:20260814")
}
