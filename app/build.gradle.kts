import java.text.SimpleDateFormat
import java.util.Date
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.hilt.android)
    alias(libs.plugins.ksp)
}

val appVersionName = "2.3.4"

fun getCurrentDayTimestamp(): String {
    val simpleDateFormat = SimpleDateFormat("yyyyMMddHHmm")
    val currentDate = Date()

    return simpleDateFormat.format(currentDate)
}

// F-Droid uses a fixed versionCode (official channel versionCode + 1).
// No getTimeBasedVersionCode()/VERSION_CODE env — F-Droid builds reproducibly.
val appVersionCode = 812888
val resolvedBuildTimestamp = getCurrentDayTimestamp()

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
    // ndkVersion required: AGP 9 dropped the implicit NDK fallback used for llvm-strip (see libs.versions.toml).
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

//    val flavorDimensionType = "type"
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
                //noinspection ChromeOsAbiSupport
                abiFilters += listOf("armeabi-v7a", "arm64-v8a")
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

        }
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
        // Exclude non-Android platform libraries (following Signal's approach)
        jniLibs {
            // AGP 9 rejects `android:extractNativeLibs="true"` in the manifest;
            // express the same intent (extract native libs at install time)
            // here instead. Preserves prior packaging behavior.
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
            // okhttp 5.x brings duplicated OSGI manifest entries
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

/**
 * Customise the generated APK filename. Migrated from the AGP 8
 * `applicationVariants.all{}` API (removed in AGP 9) to the new Variant API:
 * onVariants sets outputFileName on each APK output. Filename format matches
 * the old logic: {flavor1}-{flavor2}-v{versionName}-{versionCode}-{timestamp}-{buildType}.apk
 *
 * Reuses the file-scope appVersionCode (fixed for F-Droid) / resolvedBuildTimestamp
 * (sampled once at configuration start) so the filename's versionCode is identical to
 * the one baked into the flavor config, and every APK in one build shares the suffix.
 */
androidComponents {
    onVariants { variant ->
        val flavor1 = variant.productFlavors.getOrNull(0)?.second.orEmpty()
        val flavor2 = variant.productFlavors.getOrNull(1)?.second.orEmpty()
        val buildType = variant.buildType.orEmpty()
        variant.outputs.forEach { output ->
            // outputFileName is declared on the public VariantOutput interface;
            // only APK outputs have a settable filename (bundle outputs ignore it).
            output.outputFileName.set(
                "$flavor1-$flavor2-v$appVersionName-$appVersionCode-$resolvedBuildTimestamp-$buildType.apk"
            )
        }
    }
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

    // Bundled Conscrypt: registered as the top JSSE provider on API < 30 (see
    // TempTalkApplication.initTlsProvider) so inner TLS uses the stream-based
    // ConscryptEngineSocket. The platform Conscrypt there defaults to a raw-fd
    // socket that bypasses the outer proxy tunnel (TLS-in-TLS), breaking proxy
    // connections — including LiveKit call signaling, whose SSLSocketFactory is
    // built internally and cannot be injected from app code.
    implementation(libs.conscrypt.android)
    // Desugar JDK libs
    coreLibraryDesugaring(libs.desugar.jdk.libs)

    implementation(libs.bundles.signal)

    // WorkManager
    implementation(libs.bundles.androidx.work)

    // PictureSelector
    implementation(project(":selector"))

    // Hilt
    implementation(libs.hilt.android)
    ksp(libs.hilt.compiler)

    // 其他依赖
    implementation(libs.jwtdecode)
    debugImplementation(libs.leakcanary)
    implementation(libs.reflections)

    // 性能监控
    implementation(libs.anrwatchdog)
    // Pinned to 1.4.1: avoids transitive .module dependency verification failures. Initializer disabled in AndroidManifest (no baseline-prof.txt).
    implementation(libs.profileinstaller)
}
