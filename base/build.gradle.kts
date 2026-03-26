import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.hilt.android)
    alias(libs.plugins.kotlin.kapt)
    alias(libs.plugins.roborazzi)
    id("kotlin-parcelize")
}

android {
    namespace = "com.difft.android.base"
    compileSdk = libs.versions.compileSdk.get().toInt()

    defaultConfig {
        minSdk = libs.versions.minSdk.get().toInt()
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    kapt {
        correctErrorTypes = true
    }

    viewBinding.isEnabled = true
    buildFeatures {
        buildConfig = true
        compose = true
    }

    testOptions {
        unitTests.isIncludeAndroidResources = true
    }

    testFixtures {
        enable = true
    }

    // Workaround: kotlin-kapt does not register a Kotlin compilation task for
    // the testFixtures source set. Include testFixtures Kotlin sources in the
    // unit-test compilation so tests can use shared infrastructure.
    sourceSets.getByName("test") {
        java.srcDir("src/testFixtures/kotlin")
    }

    buildTypes {
        getByName("release") {
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

    packaging {
        // Exclude non-Android platform libraries (following Signal's approach)
        jniLibs {
            excludes += setOf(
                "**/*.dylib",
                "**/*.dll",
                "**/libsignal_jni_testing.so"
            )
            pickFirsts += setOf(
                "lib/arm64-v8a/libc++_shared.so",
                "lib/armeabi-v7a/libc++_shared.so",
                "lib/x86/libc++_shared.so",
                "lib/x86_64/libc++_shared.so"
            )
        }
        resources {
            excludes += setOf(
                "**/*.dylib",
                "**/*.dll"
            )
        }
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.fromTarget(libs.versions.jvmTarget.get()))
    }
}

roborazzi {
    outputDir.set(rootProject.file("screenshots/base"))
}

dependencies {
    // 网络相关
    api(libs.okhttp)
    api(libs.okio)
    api(libs.gson)
    api(libs.retrofit)
    api(libs.retrofit.converter.gson)
    api(libs.retrofit.converter.scalars)

    // AndroidX Core
    api(libs.androidx.core.ktx)
    api(libs.androidx.appcompat)
    api(libs.material)
    api(libs.androidx.constraintlayout)
    api(libs.androidx.activity.ktx)
    api(libs.androidx.fragment.ktx)
    api(libs.lottie)

    // AndroidX Lifecycle
    api(libs.bundles.androidx.lifecycle)

    // AndroidX Navigation
    api(libs.bundles.androidx.navigation)

    // Hilt
    api(libs.hilt.android)
    kapt(libs.hilt.compiler)
    kapt(libs.kotlin.metadata.jvm)

    // Coroutines
    api(libs.kotlinx.coroutines.android)

    // UI
    api(libs.binding)
    api(libs.glide)
    kapt(libs.glide.compiler)
    api(libs.android.svg)

    // 日志
    api(libs.timber)
    api(platform(libs.firebase.bom))
    api(libs.firebase.crashlytics.ktx)
    api(libs.slf4j.api)
    api(libs.logback.android)

    // 安全
    api(libs.security.crypto)
    api(libs.firebase.analytics.ktx)

    // 刷新布局
    api(libs.bundles.smart.refresh)

    // Compose
    val composeBom = platform(libs.compose.bom)
    api(composeBom)
    api(libs.bundles.compose)
    
    // Compose Tooling (for preview)
    api(libs.compose.ui.tooling)
    api(libs.compose.ui.tooling.preview)
    api(libs.compose.ui.test.manifest)

    // 其他
    api(libs.libphonenumber)
    implementation("net.java.dev.jna:jna:5.18.1@aar")
    api(libs.keyboard.visibility.event)

    // Protobuf
    api(libs.protobuf.javalite)
    api(libs.protobuf.kotlin.lite)

    // Foldable screen support
    api(libs.androidx.window)

    // Test dependencies
    testImplementation(libs.junit)
    testImplementation(libs.kotlin.test)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.mockk)
    testImplementation(libs.turbine)
    testImplementation(libs.robolectric)
    // Compose test
    testImplementation(platform(libs.compose.bom))
    testImplementation(libs.compose.ui.test.junit4)
    debugImplementation(platform(libs.compose.bom))
    debugImplementation(libs.compose.ui.test.manifest)
    // Roborazzi
    testImplementation(libs.roborazzi)
    testImplementation(libs.roborazzi.compose)
    testImplementation(libs.roborazzi.junit.rule)
    // testFixtures source set dependencies
    testFixturesImplementation(libs.junit)
    testFixturesImplementation(libs.kotlinx.coroutines.test)
    testFixturesImplementation(libs.mockk)
}