plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
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
    kotlinOptions {
        jvmTarget = libs.versions.jvmTarget.get()
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

    composeOptions {
        kotlinCompilerExtensionVersion = libs.versions.composeCompiler.get()
    }
    buildFeatures {
        buildConfig = true
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

    // Login specific dependencies
    implementation(libs.signal.client)
    implementation(libs.protobuf.javalite)
    
    // Retrofit (explicit for KAPT)
    implementation(libs.retrofit)
    implementation(libs.retrofit.converter.gson)
    implementation(libs.retrofit.adapter.rxjava3)

    // PictureSelector
    implementation(project(":selector"))
    implementation(libs.picture.selector.ucrop)
    implementation(libs.picture.selector.compress)

    // 测试依赖已通过base模块提供

    implementation(libs.keyboard.visibility.event)
}