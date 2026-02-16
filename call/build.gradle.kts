import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.hilt.android)
    alias(libs.plugins.kotlin.kapt)
    id("kotlin-parcelize")
    id("org.jetbrains.kotlin.plugin.serialization")
}

android {
    namespace = "com.difft.android.call"

    compileSdk = libs.versions.compileSdk.get().toInt()

    defaultConfig {
        minSdk = libs.versions.minSdk.get().toInt()
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    buildFeatures {
        viewBinding = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kapt {
        correctErrorTypes = true
    }

    viewBinding.isEnabled = true


    buildFeatures {
        compose = true
    }

    buildFeatures {
        buildConfig = true
    }

}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.fromTarget(libs.versions.jvmTarget.get()))
    }
}

dependencies {
    implementation(project(":network"))
    implementation(project(":base"))
    implementation(project(":database"))

    // Hilt
    implementation(libs.hilt.android)
    kapt(libs.hilt.compiler)
    kapt(libs.kotlin.metadata.jvm)

    // Compose Debug
    debugImplementation(libs.bundles.compose.debug)
    // 测试依赖已通过base模块提供

    // Call specific dependencies
    implementation(libs.accompanist.systemuicontroller)
    implementation(libs.accompanist.permissions)
    implementation(libs.livekit.android)
    implementation(libs.livekit.android.camerax)
    implementation(libs.livekit.ttsignal)
    implementation(libs.denoise.filter)
    implementation(libs.coil.compose)
    implementation(libs.coil)
    implementation(libs.coil.gif)
    implementation(libs.compose.foundation.version)
    implementation(libs.kotlinx.serialization.core)
    implementation(libs.kotlinx.serialization.json)

}