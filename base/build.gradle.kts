import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.hilt.android)
    alias(libs.plugins.ksp)
    alias(libs.plugins.kotlin.serialization)
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

    buildFeatures {
        viewBinding = true
        buildConfig = true
        compose = true
    }

    testOptions {
        unitTests.isIncludeAndroidResources = true
    }

    testFixtures {
        enable = true
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
    // Explicit on f-droid: the GMS/Firebase deps that transitively provided
    // LocalBroadcastManager (used by BroadcastHelper) are stripped here.
    api("androidx.localbroadcastmanager:localbroadcastmanager:1.0.0")
    api(libs.lottie)

    // AndroidX Lifecycle
    api(libs.bundles.androidx.lifecycle)

    // AndroidX Navigation
    api(libs.bundles.androidx.navigation)

    // Hilt
    api(libs.hilt.android)
    ksp(libs.hilt.compiler)

    // Coroutines
    api(libs.kotlinx.coroutines.android)

    // UI
    api(libs.binding)
    api(libs.glide)
    ksp(libs.glide.ksp)
    // zjupure webp decoder — animated WebP via @GlideModule (api exposes WebpDrawable/Transformation
    // to :chat). Kept on Glide 5: its built-in AnimatedImageDecoder is unreliable for WebP in lists
    // (bumptech #5176/#5477). Built for 4.16 but runs unchanged on 5.0.7.
    api(libs.glide.webpdecoder)
    api(libs.android.svg)

    // 日志
    api(libs.timber)
    api(libs.slf4j.api)
    api(libs.logback.android)

    // 安全
    // Retained for the legacy EncryptedSharedPreferences reader path used by storage
    // migration lambdas (issue #725 v(N+1) → v(N+4) retention window).
    api(libs.security.crypto)

    // Storage layer (issue #725): DataStore + Tink AEAD + kotlinx-serialization protobuf.
    // Exported as api(...) so :app, :network, :call, :chat, :database can inject the
    // storage qualifiers (`@SecureUserDataStore`, `@SecureConfigDataStore`, `@AppStateDataStore`)
    // without per-module dependency declarations.
    api(libs.datastore)
    api(libs.datastore.preferences)
    api(libs.tink.android)
    api(libs.kotlinx.serialization.protobuf)

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
    api(libs.dtproto) {
        exclude(group = "net.java.dev.jna", module = "jna")
    }
    api(libs.jna) { artifact { type = "aar" } }
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
    // ShadowThrowingService is a Robolectric @Implements shadow living in
    // testFixtures. AGP 9 built-in Kotlin compiles testFixtures as its own
    // Kotlin source set (kapt previously folded it into the test compilation,
    // where it inherited testImplementation(robolectric)); the dependency must
    // now be declared on the testFixtures classpath explicitly.
    testFixturesImplementation(libs.robolectric)
}