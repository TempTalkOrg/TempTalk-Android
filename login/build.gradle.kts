import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.ksp)
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

    
    implementation(libs.hilt.android)
    ksp(libs.hilt.compiler)

    
    implementation(libs.signal.android)

    
    implementation(libs.retrofit)
    implementation(libs.retrofit.converter.gson)

    
    implementation(project(":selector"))
    implementation(libs.picture.selector.ucrop)

    
    testImplementation(libs.junit)
    testImplementation(libs.kotlin.test)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.mockk)
    testImplementation(libs.turbine)
    testImplementation(libs.robolectric)
    testImplementation(libs.hilt.android.testing)
    kspTest(libs.hilt.compiler)
    testImplementation(testFixtures(project(":base")))
    
    testImplementation(platform(libs.compose.bom))
    testImplementation(libs.compose.ui.test.junit4)
    
    testImplementation(libs.roborazzi)
    testImplementation(libs.roborazzi.compose)
    testImplementation(libs.roborazzi.junit.rule)
}