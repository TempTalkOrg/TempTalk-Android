plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
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
    kotlinOptions {
        jvmTarget = libs.versions.jvmTarget.get()
    }

    kapt {
        correctErrorTypes = true
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

    // 测试依赖已通过base模块提供

    // WorkManager dependencies
    implementation(libs.bundles.androidx.work)

    // PictureSelector
    implementation(libs.picture.selector.ucrop)
    implementation(libs.picture.selector.compress)

    implementation(libs.bundles.signal)
    implementation(libs.bundles.jackson)
    

    // SQLite
    implementation(libs.androidx.sqlite)
    implementation(libs.androidx.sqlite.ktx)
    implementation(libs.signal.database.sqlcipher)

    // Preference
    implementation(libs.androidx.preference)

    // Other dependencies
    implementation(libs.annimon.stream)
    implementation(libs.eventbus)
    implementation(libs.guava)
    implementation(libs.keyboard.visibility.event)
    implementation(libs.circle.imageview)
    implementation(libs.zxing)
    implementation(libs.legacy.support.v4)
    implementation(libs.okhttp.tls)
    implementation(libs.lifecycle.reactivestreams.ktx)
    implementation(libs.media3.common)
    implementation(libs.media3.exoplayer)
    implementation(libs.media3.ui)
    implementation(libs.mlkit.translate)
    implementation(libs.language.detector) {
        exclude(group = "com.intellij", module = "annotations")
    }
}