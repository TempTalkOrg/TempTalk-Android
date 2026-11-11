import java.text.SimpleDateFormat
import java.util.Date
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.hilt.android)
    alias(libs.plugins.ksp)
    alias(libs.plugins.roborazzi)
    id("kotlin-parcelize")
}

val appVersionName = "2.5.0"

fun getCurrentDayTimestamp(): String {
    val simpleDateFormat = SimpleDateFormat("yyyyMMddHHmm")
    val currentDate = Date()

    return simpleDateFormat.format(currentDate)
}



val appVersionCode = 977163
val resolvedBuildTimestamp = getCurrentDayTimestamp()

fun getBuildTime(): String {
    return System.currentTimeMillis().toString()
}

android {
    namespace = "com.difft.android"
    compileSdk = libs.versions.compileSdk.get().toInt()
    
    ndkVersion = libs.versions.ndk.get()

    defaultConfig {
        minSdk = libs.versions.minSdk.get().toInt()
        targetSdk = libs.versions.targetSdk.get().toInt()
        multiDexEnabled = true
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    androidResources {
        localeFilters += setOf("en", "zh", "en-rUS", "zh-rCN")
    }

    val flavorDimensionEnvironment = "environment"
    val flavorDimensionChannel = "channel"
    flavorDimensions += setOf(flavorDimensionEnvironment, flavorDimensionChannel)

    productFlavors {
        val ENVIRONMENT_DEVELOPMENT = "EnvironmentDevelopment"
        val ENVIRONMENT_ONLINE = "EnvironmentOnline"

        create("TTDev") {
            dimension = flavorDimensionEnvironment

            applicationId = "org.difft.temptalk.test"
            versionCode = appVersionCode
            versionName = appVersionName

            buildConfigField("String", "APP_TYPE", "\"${this.name}\"")
            buildConfigField("String", "ENVIRONMENT_DEVELOPMENT", "\"$ENVIRONMENT_DEVELOPMENT\"")
            buildConfigField("String", "ENVIRONMENT_ONLINE", "\"$ENVIRONMENT_ONLINE\"")
            buildConfigField("String", "ENVIRONMENT", "\"$ENVIRONMENT_DEVELOPMENT\"")
            buildConfigField("String", "BUILD_TIME", "\"${getBuildTime()}\"")

            manifestPlaceholders.apply {
                this["APP_SCHEME_VALUE1"] = "chative"
                this["APP_SCHEME_VALUE2"] = "temptalk"

            }
        }

        create("TTOnline") {
            dimension = flavorDimensionEnvironment

            applicationId = "org.difft.temptalk"
            versionCode = appVersionCode
            versionName = appVersionName

            buildConfigField("String", "APP_TYPE", "\"${this.name}\"")
            buildConfigField("String", "ENVIRONMENT_DEVELOPMENT", "\"$ENVIRONMENT_DEVELOPMENT\"")
            buildConfigField("String", "ENVIRONMENT_ONLINE", "\"$ENVIRONMENT_ONLINE\"")
            buildConfigField("String", "ENVIRONMENT", "\"$ENVIRONMENT_ONLINE\"")
            buildConfigField("String", "BUILD_TIME", "\"${getBuildTime()}\"")

            manifestPlaceholders.apply {
                this["APP_SCHEME_VALUE1"] = "chative"
                this["APP_SCHEME_VALUE2"] = "temptalk"
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

        create("fdroid") {
            dimension = flavorDimensionChannel

            buildConfigField("String", "APP_CHANNEL", "\"${this.name}\"")
        }
    }

    
    
    sourceSets {
        listOf("google", "official", "insider").forEach { channel ->
            getByName(channel).kotlin.srcDir("src/gms/java")
            getByName("test${channel.replaceFirstChar { it.uppercase() }}")
                .kotlin.srcDir("src/testGms/java")
        }

        
        
        
        
        listOf("official", "insider").forEach { channel ->
            getByName(channel).kotlin.srcDir("src/sideload/java")
            getByName(channel).manifest.srcFile("src/sideload/AndroidManifest.xml")
            getByName("test${channel.replaceFirstChar { it.uppercase() }}")
                .kotlin.srcDir("src/testSideload/java")
        }
    }

    signingConfigs {
        
        
        
        val sharedDebugKey = rootProject.file("chative_debug.key")
        val sharedDebugStorePassword = providers.gradleProperty("sharedDebugStorePassword").orNull
        if (sharedDebugKey.exists() && sharedDebugStorePassword != null) {
            create("sharedDebug") {
                storeFile = sharedDebugKey
                storePassword = sharedDebugStorePassword
                keyAlias = providers.gradleProperty("sharedDebugKeyAlias").get()
                keyPassword = providers.gradleProperty("sharedDebugKeyPassword").get()
            }
        }
    }

    buildTypes {
        debug {
            ndk {
                
                abiFilters += listOf("armeabi-v7a", "arm64-v8a")
            }
            isMinifyEnabled = false
        }
        release {
            ndk {
                
                abiFilters += listOf("armeabi-v7a", "arm64-v8a")
            }
            isShrinkResources = false
            isMinifyEnabled = false
            isDebuggable = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro"
            )
        }
    }

    testOptions {
        
        
        
        unitTests.all { it.maxHeapSize = "2g" }
    }

    compileOptions {
        isCoreLibraryDesugaringEnabled = true
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        viewBinding = true
        buildConfig = true
        compose = true
    }

    packaging {
        
        jniLibs {
            
            useLegacyPackaging = true
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
            
            pickFirsts += setOf(
                "META-INF/versions/9/OSGI-INF/MANIFEST.MF"
            )
        }
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.fromTarget(libs.versions.jvmTarget.get()))
    }
}





androidComponents {
    val sharedDebugSigning = android.signingConfigs.findByName("sharedDebug")
    
    onVariants(selector().withBuildType("debug").withFlavor("environment" to "TTDev")) { variant ->
        sharedDebugSigning?.let { variant.signingConfig.setConfig(it) }
    }
    onVariants { variant ->
        val flavor1 = variant.productFlavors.getOrNull(0)?.second.orEmpty()
        val flavor2 = variant.productFlavors.getOrNull(1)?.second.orEmpty()
        val buildType = variant.buildType.orEmpty()
        variant.outputs.forEach { output ->
            
            
            output.outputFileName.set(
                "$flavor1-$flavor2-v$appVersionName-$appVersionCode-$resolvedBuildTimestamp-$buildType.apk"
            )
        }
    }
}

roborazzi {
    outputDir.set(rootProject.file("screenshots/app"))
}


extensions.configure<dev.detekt.gradle.extensions.DetektExtension> {
    source.from(
        files(
            "src/gms/java",
            "src/fdroid/java",
            "src/google/java",
            "src/sideload/java",
            "src/testGms/java",
            "src/testFdroid/java",
            "src/testGoogle/java",
            "src/testSideload/java",
        )
    )
}

hilt {
    enableAggregatingTask = true
}
dependencies {
    implementation(project(":base"))
    implementation(project(":chat"))
    implementation(project(":network"))
    implementation(project(":login"))
    implementation(project(":database"))
    implementation(project(":security"))
    implementation(project(":call"))

    
    implementation(project(":p2p"))
    
    
    
    
    
    
    implementation(libs.conscrypt.android)
    
    coreLibraryDesugaring(libs.desugar.jdk.libs)

    implementation(libs.bundles.signal)

    
    implementation(libs.bundles.androidx.work)

    
    implementation(project(":selector"))

    
    implementation(libs.hilt.android)
    ksp(libs.hilt.compiler)


    implementation(libs.jwtdecode)
    debugImplementation(libs.leakcanary)
    implementation(libs.reflections)

    implementation(libs.anrwatchdog)
    
    implementation(libs.profileinstaller)
}
