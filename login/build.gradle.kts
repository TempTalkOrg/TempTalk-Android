import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.kapt)
    alias(libs.plugins.hilt.android)
    id("kotlin-parcelize")
}

android {
    namespace = "com.difft.android.login"

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

    val flavorDimensionType = "environment"
    val flavorDimensionChannel = "channel"
    flavorDimensions += setOf(flavorDimensionType, flavorDimensionChannel)

    productFlavors {
        create("TTDev") {
            dimension = flavorDimensionType
        }
        create("TTOnline") {
            dimension = flavorDimensionType
        }
        create("google") {
            dimension = flavorDimensionChannel
        }
        create("official") {
            dimension = flavorDimensionChannel
        }
        create("insider") {
            dimension = flavorDimensionChannel
        }
    }

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
    implementation(project(":chat"))
    implementation(project(":database"))

    // Hilt
    implementation(libs.hilt.android)
    kapt(libs.hilt.compiler)
    kapt(libs.kotlin.metadata.jvm)

    // Login specific dependencies
    implementation(libs.signal.android)

    // SMS Retriever API for auto-fill
    implementation(libs.play.services.auth.api.phone)
    
    // Retrofit (explicit for KAPT)
    implementation(libs.retrofit)
    implementation(libs.retrofit.converter.gson)
    implementation(libs.retrofit.adapter.rxjava3)

    // PictureSelector
    implementation(project(":selector"))
    implementation(libs.picture.selector.ucrop)
    implementation(libs.picture.selector.compress)
}