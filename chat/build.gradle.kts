import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.hilt.android)
    alias(libs.plugins.kotlin.kapt)
    id("kotlin-parcelize")
    id("androidx.navigation.safeargs.kotlin")
}

android {
    namespace = "com.difft.android.chat"

    compileSdk = libs.versions.compileSdk.get().toInt()

    defaultConfig {
        minSdk = libs.versions.minSdk.get().toInt()
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    buildFeatures {
        viewBinding = true
    }

    viewBinding.isEnabled = true

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kapt {
        correctErrorTypes = true
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
    implementation(project(":base"))
    implementation(project(":network"))
    implementation(project(":database"))
    implementation(project(":video"))
    implementation(project(":image-editor"))
    implementation(project(":selector"))
    implementation(project(":call"))

    // Hilt
    implementation(libs.hilt.android)
    kapt(libs.hilt.compiler)
    kapt(libs.kotlin.metadata.jvm)

    // 测试依赖已通过base模块提供

    // WorkManager dependencies
    implementation(libs.bundles.androidx.work)

    // Image compression (Luban)
    implementation(libs.picture.selector.compress)

    implementation(libs.bundles.signal)
    implementation(libs.bundles.jackson)
    

    // SQLite
    implementation(libs.androidx.sqlite)
    implementation(libs.androidx.sqlite.ktx)
    implementation(libs.signal.sqlcipher.android)

    // Preference
    implementation(libs.androidx.preference)

    // Other dependencies
    implementation(libs.annimon.stream)
    implementation(libs.keyboard.visibility.event)
    implementation(libs.circle.imageview)
    implementation(libs.zxing)
    implementation(libs.legacy.support.v4)
    implementation(libs.lifecycle.reactivestreams.ktx)
    implementation(libs.media3.common)
    implementation(libs.media3.exoplayer)
    implementation(libs.media3.ui)
    implementation(libs.mlkit.translate)
    implementation(libs.language.detector) {
        exclude(group = "com.intellij", module = "annotations")
    }
}