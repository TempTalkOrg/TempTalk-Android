import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.kapt)
    alias(libs.plugins.hilt.android)
    alias(libs.plugins.roborazzi)
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

    val flavorDimensionEnvironment = "environment"
    flavorDimensions += flavorDimensionEnvironment

    productFlavors {
        create("TTDev") {
            dimension = flavorDimensionEnvironment
        }
        create("TTOnline") {
            dimension = flavorDimensionEnvironment
        }
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
    outputDir.set(rootProject.file("screenshots/login"))
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

    // PictureSelector
    implementation(project(":selector"))
    implementation(libs.picture.selector.ucrop)
    implementation(libs.picture.selector.compress)

    // Test dependencies
    testImplementation(libs.junit)
    testImplementation(libs.kotlin.test)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.mockk)
    testImplementation(libs.turbine)
    testImplementation(libs.robolectric)
    testImplementation(libs.hilt.android.testing)
    kaptTest(libs.hilt.compiler)
    testImplementation(testFixtures(project(":base")))
    // Compose test
    testImplementation(platform(libs.compose.bom))
    testImplementation(libs.compose.ui.test.junit4)
    // Roborazzi
    testImplementation(libs.roborazzi)
    testImplementation(libs.roborazzi.compose)
    testImplementation(libs.roborazzi.junit.rule)
}