import java.text.SimpleDateFormat
import java.util.Date

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.hilt.android)
    alias(libs.plugins.kotlin.kapt)
    alias(libs.plugins.google.services)
    alias(libs.plugins.firebase.crashlytics)
    alias(libs.plugins.firebase.perf)
}
val appVersionName = "2.0.0"

fun getCurrentDayTimestamp(): String {
    val simpleDateFormat = SimpleDateFormat("yyyyMMddHHmm")
    val currentDate = Date()

    return simpleDateFormat.format(currentDate)
}

fun getTimeBasedVersionCode(): Int {
    val baseTime = 1735689600000L // 2025-01-01 00:00:00 UTC
    val currentTime = System.currentTimeMillis()
    val timeDiff = currentTime - baseTime
    return (timeDiff / (1000 * 60)).toInt()
}

fun getBuildTime(): String {
    return System.currentTimeMillis().toString()
}

fun getStoreFile(): String {
    if (properties.contains("storeFile")) {
        return properties["storeFile"].toString()
    }
    return "/Users/difft/.ssh/DifftKey/chative.key"
}

fun getStorePassword(): String? {
    if (properties.contains("storePassword")) {
        return properties["storePassword"].toString()
    }
    return System.getenv("storePassword")
}

fun getKeyAlias(): String? {
    if (properties.contains("keyAlias")) {
        return properties["keyAlias"].toString()
    }
    return System.getenv("keyAlias")
}

fun getKeyPassword(): String? {
    if (properties.contains("keyPassword")) {
        return properties["keyPassword"].toString()
    }
    return System.getenv("keyPassword")
}

android {
    namespace = "com.difft.android"
    compileSdk = libs.versions.compileSdk.get().toInt()

    defaultConfig {
        minSdk = libs.versions.minSdk.get().toInt()
        targetSdk = libs.versions.targetSdk.get().toInt()
        multiDexEnabled = true
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        resourceConfigurations.addAll(listOf("en", "zh", "en-rUS", "zh-rCN"))
    }

    buildFeatures {
        viewBinding = true
    }
//    val flavorDimensionType = "type"
    val flavorDimensionEnvironment = "environment"
    val flavorDimensionChannel = "channel"
    flavorDimensions += setOf(flavorDimensionEnvironment, flavorDimensionChannel)

    productFlavors {
        val ENVIRONMENT_DEVELOPMENT = "EnvironmentDevelopment"
        val ENVIRONMENT_ONLINE = "EnvironmentOnline"

        create("TTDev") {
            dimension = flavorDimensionEnvironment

            applicationId = "org.difft.chative.test"
            versionCode = getTimeBasedVersionCode()
            versionName = appVersionName

            buildConfigField("String", "APP_TYPE", "\"${this.name}\"")
            buildConfigField("String", "ENVIRONMENT_DEVELOPMENT", "\"$ENVIRONMENT_DEVELOPMENT\"")
            buildConfigField("String", "ENVIRONMENT_ONLINE", "\"$ENVIRONMENT_ONLINE\"")
            buildConfigField("String", "ENVIRONMENT", "\"$ENVIRONMENT_DEVELOPMENT\"")
            buildConfigField("String", "BUILD_TIME", "\"${getBuildTime()}\"")

            manifestPlaceholders.apply {
                this["APP_SCHEME_VALUE1"] = "chative"
                this["APP_SCHEME_VALUE2"] = "temptalk"

                this["FIREBASE_ANALYTICS_ENABLED"] = true
                this["FIREBASE_CRASHLYTICS_ENABLED"] = true
                this["FIREBASE_PERFORMANCE_ENABLED"] = true
            }
        }

        create("TTOnline") {
            dimension = flavorDimensionEnvironment

            applicationId = "org.difft.chative"
            versionCode = getTimeBasedVersionCode()
            versionName = appVersionName

            buildConfigField("String", "APP_TYPE", "\"${this.name}\"")
            buildConfigField("String", "ENVIRONMENT_DEVELOPMENT", "\"$ENVIRONMENT_DEVELOPMENT\"")
            buildConfigField("String", "ENVIRONMENT_ONLINE", "\"$ENVIRONMENT_ONLINE\"")
            buildConfigField("String", "ENVIRONMENT", "\"$ENVIRONMENT_ONLINE\"")
            buildConfigField("String", "BUILD_TIME", "\"${getBuildTime()}\"")

            manifestPlaceholders.apply {
                this["APP_SCHEME_VALUE1"] = "chative"
                this["APP_SCHEME_VALUE2"] = "temptalk"

                this["FIREBASE_ANALYTICS_ENABLED"] = true
                this["FIREBASE_CRASHLYTICS_ENABLED"] = true
                this["FIREBASE_PERFORMANCE_ENABLED"] = true
            }
        }

        create("google") {
            dimension = flavorDimensionChannel

            buildConfigField("String", "APP_CHANNEL", "\"${this.name}\"")
        }

        create("official") {
            dimension = flavorDimensionChannel

            buildConfigField("String", "APP_CHANNEL", "\"${this.name}\"")
        }

        create("insider") {
            dimension = flavorDimensionChannel

            buildConfigField("String", "APP_CHANNEL", "\"${this.name}\"")
        }
    }

    signingConfigs {
        create("release") {
            storeFile = file(getStoreFile())
            storePassword = getStorePassword()
            keyAlias = getKeyAlias()
            keyPassword = getKeyPassword()
        }
    }

    buildTypes {
        debug {
            ndk {
                abiFilters += listOf("armeabi", "armeabi-v7a", "arm64-v8a", "x86", "x86_64")
            }
            isMinifyEnabled = false
        }
        release {
            ndk {
                //noinspection ChromeOsAbiSupport
                abiFilters += listOf("armeabi-v7a", "arm64-v8a")
            }
            isShrinkResources = false
            isMinifyEnabled = false
            isDebuggable = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro"
            )
            signingConfig = signingConfigs.getByName("release")
        }
    }

    /**
     * 修改生成的 apk 文件名
     */
    android.applicationVariants.all {
        val flavorName1 = this.productFlavors[0].name
        var flavorName2 = ""
        if (this.productFlavors.size > 1) {
            flavorName2 = this.productFlavors[1].name
        }
        val buildType = this.buildType.name
        val versionName = this.versionName
        val versionCode = this.versionCode
        outputs.all {
            if (this is com.android.build.gradle.internal.api.ApkVariantOutputImpl) {
                this.outputFileName = "${flavorName1}-${flavorName2}-v${versionName}-${versionCode}-${getCurrentDayTimestamp()}-${buildType}.apk"
            }
        }
    }

    compileOptions {
        isCoreLibraryDesugaringEnabled = true
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = libs.versions.jvmTarget.get()
    }

    buildFeatures {
        viewBinding = true
        dataBinding = true
        buildConfig = true
        compose = true
    }

    composeOptions {
        kotlinCompilerExtensionVersion = libs.versions.composeCompiler.get()
    }

    packaging {
        jniLibs.pickFirsts.add("lib/arm64-v8a/libc++_shared.so")
        jniLibs.pickFirsts.add("lib/armeabi-v7a/libc++_shared.so")
        jniLibs.pickFirsts.add("lib/x86/libc++_shared.so")
        jniLibs.pickFirsts.add("lib/x86_64/libc++_shared.so")
    }
}

// Allow references to generated code
kapt {
    correctErrorTypes = true
}

hilt {
    enableAggregatingTask = true
}
dependencies {
    // 项目模块依赖
    implementation(project(":base"))
    implementation(project(":chat"))
    implementation(project(":network"))
    implementation(project(":login"))
    implementation(project(":database"))
    implementation(project(":security"))
    implementation(project(":call"))

    // Desugar JDK libs
    coreLibraryDesugaring(libs.desugar.jdk.libs)

    implementation(libs.bundles.signal)

    // WorkManager
    implementation(libs.bundles.androidx.work)

    // Firebase
    implementation(platform(libs.firebase.bom))
    implementation(libs.bundles.firebase)

    // PictureSelector
    implementation(project(":selector"))
    implementation(libs.bundles.picture.selector)

    // Hilt
    implementation(libs.hilt.android)
    kapt(libs.hilt.compiler)

    // 其他依赖
    implementation(libs.jwtdecode)
    debugImplementation(libs.leakcanary)
    implementation(libs.play.services.base)
    implementation(libs.reflections)

    // 性能监控
    implementation(libs.koom.java.leak)
    implementation(libs.anrwatchdog)
    implementation(libs.profileinstaller)
}
