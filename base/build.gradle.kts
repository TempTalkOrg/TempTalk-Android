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
        
        
        
        unitTests.all { it.maxHeapSize = "2g" }
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
    
    api(libs.okhttp)
    api(libs.okio)
    api(libs.gson)
    api(libs.retrofit)
    api(libs.retrofit.converter.gson)
    api(libs.retrofit.converter.scalars)

    
    api(libs.androidx.core.ktx)
    api(libs.androidx.appcompat)
    api(libs.material)
    api(libs.androidx.constraintlayout)
    api(libs.androidx.activity.ktx)
    api(libs.androidx.fragment.ktx)
    
    
    api("androidx.localbroadcastmanager:localbroadcastmanager:1.0.0")
    implementation(libs.androidx.exifinterface)

    
    api(libs.bundles.androidx.lifecycle)

    
    api(libs.bundles.androidx.navigation)

    
    api(libs.hilt.android)
    ksp(libs.hilt.compiler)

    
    api(libs.kotlinx.coroutines.android)

    
    api(libs.binding)
    api(libs.glide)
    ksp(libs.glide.ksp)
    
    
    api(libs.glide.webpdecoder)
    api(libs.android.svg)

    
    api(libs.timber)
    api(libs.slf4j.api)
    api(libs.logback.android)

    
    
    
    api(libs.security.crypto)

    
    
    
    
    api(libs.datastore)
    api(libs.datastore.preferences)
    api(libs.tink.android)
    api(libs.kotlinx.serialization.protobuf)

    
    api(libs.bundles.smart.refresh)

    
    val composeBom = platform(libs.compose.bom)
    api(composeBom)
    api(libs.bundles.compose)
    api(libs.kyant.backdrop)
    
    
    api(libs.compose.ui.tooling)
    api(libs.compose.ui.tooling.preview)
    api(libs.compose.ui.test.manifest)

    
    api(libs.libphonenumber)
    api(libs.dtproto) {
        exclude(group = "net.java.dev.jna", module = "jna")
    }
    api(libs.jna) { artifact { type = "aar" } }
    api(libs.keyboard.visibility.event)

    
    api(libs.protobuf.javalite)
    api(libs.protobuf.kotlin.lite)

    
    api(libs.androidx.window)

    
    testImplementation(libs.junit)
    testImplementation(libs.kotlin.test)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.mockk)
    testImplementation(libs.turbine)
    testImplementation(libs.robolectric)
    
    testImplementation(platform(libs.compose.bom))
    testImplementation(libs.compose.ui.test.junit4)
    debugImplementation(platform(libs.compose.bom))
    debugImplementation(libs.compose.ui.test.manifest)
    
    testImplementation(libs.roborazzi)
    testImplementation(libs.roborazzi.compose)
    testImplementation(libs.roborazzi.junit.rule)
    
    testFixturesImplementation(libs.junit)
    testFixturesImplementation(libs.kotlinx.coroutines.test)
    testFixturesImplementation(libs.mockk)
    
    
    testFixturesImplementation(libs.robolectric)
}
