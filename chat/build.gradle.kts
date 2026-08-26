import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.hilt.android)
    alias(libs.plugins.ksp)
    alias(libs.plugins.roborazzi)
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

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
    }

    buildFeatures {
        buildConfig = true
    }

    testOptions {
        unitTests.isIncludeAndroidResources = true
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.fromTarget(libs.versions.jvmTarget.get()))
    }
}

roborazzi {
    outputDir.set(rootProject.file("screenshots/chat"))
}

dependencies {
    implementation(project(":base"))
    implementation(project(":network"))
    implementation(project(":database"))
    implementation(project(":video"))
    implementation(project(":image-editor"))
    implementation(project(":selector"))
    implementation(project(":call"))

    // Voice message dual-candidate recorder (denoise + voice changer pipeline).
    // :call already transitively pulls this for the RTC voice changer, but
    // :chat needs it as a direct dependency to compile against the new
    // OfflineAudioPipeline / PipelineTapConfig types in `voice/`.
    implementation(libs.denoise.filter)

    // Hilt
    implementation(libs.hilt.android)
    ksp(libs.hilt.compiler)

    // Test dependencies
    testImplementation(libs.junit)
    testImplementation(libs.kotlin.test)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.mockk)
    testImplementation(libs.turbine)
    testImplementation(libs.robolectric)
    testImplementation(libs.hilt.android.testing)
    kspTest(libs.hilt.compiler)
    testImplementation(testFixtures(project(":base")))
    testImplementation(testFixtures(project(":database")))
    // Compose test
    testImplementation(platform(libs.compose.bom))
    testImplementation(libs.compose.ui.test.junit4)
    // Roborazzi
    testImplementation(libs.roborazzi)
    testImplementation(libs.roborazzi.compose)
    testImplementation(libs.roborazzi.junit.rule)

    // WorkManager dependencies
    implementation(libs.bundles.androidx.work)

    // Image compression (Luban)
    implementation(libs.picture.selector.compress)

    implementation(libs.bundles.signal)
    implementation(libs.bundles.jackson)
    

    // SQLite
    implementation(libs.androidx.sqlite)
    implementation(libs.androidx.sqlite.ktx)

    // Preference
    implementation(libs.androidx.preference)

    // Other dependencies
    implementation(libs.annimon.stream)
    implementation(libs.keyboard.visibility.event)
    implementation(libs.circle.imageview)
    implementation(libs.androidx.camera.core)
    implementation(libs.androidx.camera.camera2)
    implementation(libs.androidx.camera.lifecycle)
    implementation(libs.androidx.camera.view)
    implementation(libs.zxing.core)
    implementation(libs.legacy.support.v4)
    implementation(libs.media3.common)
    implementation(libs.media3.exoplayer)
    implementation(libs.media3.ui)
    implementation(libs.language.detector) {
        exclude(group = "com.intellij", module = "annotations")
    }
}