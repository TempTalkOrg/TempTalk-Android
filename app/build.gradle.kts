import java.text.SimpleDateFormat
import java.util.Date
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.hilt.android)
    alias(libs.plugins.ksp)
    alias(libs.plugins.google.services)
    alias(libs.plugins.firebase.crashlytics)
    alias(libs.plugins.firebase.perf)
    alias(libs.plugins.roborazzi)
}

val appVersionName = "2.4.8"

fun getCurrentDayTimestamp(): String {
    val simpleDateFormat = SimpleDateFormat("yyyyMMddHHmm")
    val currentDate = Date()

    return simpleDateFormat.format(currentDate)
}

fun getTimeBasedVersionCode(): Int {
    System.getenv("VERSION_CODE")?.toIntOrNull()?.let { return it }
    val baseTime = 1735689600000L // 2025-01-01 00:00:00 UTC
    val currentTime = System.currentTimeMillis()
    val timeDiff = currentTime - baseTime
    return (timeDiff / (1000 * 60)).toInt()
}

// Sampled ONCE at configuration start and reused everywhere (both product
// flavors' versionCode AND the APK output filename). getTimeBasedVersionCode()
// is minute-resolution wall-clock, so independent calls could disagree across a
// minute boundary in a local build without the VERSION_CODE env var — making the
// filename's versionCode differ from the one baked into the APK manifest.
val resolvedVersionCode = getTimeBasedVersionCode()
val resolvedBuildTimestamp = getCurrentDayTimestamp()

fun getBuildTime(): String {
    return System.currentTimeMillis().toString()
}

fun getStoreFile(): String {
    if (properties.contains("storeFile")) {
        return properties["storeFile"].toString()
    }
    return "/Users/difft/.ssh/DifftKey/temptalk.key"
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
            versionCode = resolvedVersionCode
            versionName = appVersionName

            buildConfigField("String", "APP_TYPE", "\"${this.name}\"")
            buildConfigField("String", "ENVIRONMENT_DEVELOPMENT", "\"$ENVIRONMENT_DEVELOPMENT\"")
            buildConfigField("String", "ENVIRONMENT_ONLINE", "\"$ENVIRONMENT_ONLINE\"")
            buildConfigField("String", "ENVIRONMENT", "\"$ENVIRONMENT_DEVELOPMENT\"")
            buildConfigField("String", "BUILD_TIME", "\"${getBuildTime()}\"")

            manifestPlaceholders.apply {
                this["APP_SCHEME_VALUE1"] = "chative"
                this["APP_SCHEME_VALUE2"] = "temptalk"

                this["FIREBASE_CRASHLYTICS_ENABLED"] = true
                this["FIREBASE_PERFORMANCE_ENABLED"] = true
            }
        }

        create("TTOnline") {
            dimension = flavorDimensionEnvironment

            applicationId = "org.difft.temptalk"
            versionCode = resolvedVersionCode
            versionName = appVersionName

            buildConfigField("String", "APP_TYPE", "\"${this.name}\"")
            buildConfigField("String", "ENVIRONMENT_DEVELOPMENT", "\"$ENVIRONMENT_DEVELOPMENT\"")
            buildConfigField("String", "ENVIRONMENT_ONLINE", "\"$ENVIRONMENT_ONLINE\"")
            buildConfigField("String", "ENVIRONMENT", "\"$ENVIRONMENT_ONLINE\"")
            buildConfigField("String", "BUILD_TIME", "\"${getBuildTime()}\"")

            manifestPlaceholders.apply {
                this["APP_SCHEME_VALUE1"] = "chative"
                this["APP_SCHEME_VALUE2"] = "temptalk"

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

        create("fdroid") {
            dimension = flavorDimensionChannel

            buildConfigField("String", "APP_CHANNEL", "\"${this.name}\"")
        }
    }

    // One shared source set for every channel that may ship Google proprietary code, holding the
    // ML Kit translate backend and its unit tests. The fdroid channel deliberately maps neither and
    // compiles src/fdroid/java + src/testFdroid/java instead, so dropping ML Kit for F-Droid is a
    // dependency change only.
    // kotlin.srcDir, not java.srcDir: with AGP 9's built-in Kotlin support the Kotlin compilation
    // reads the `kotlin` source directory set, and a java-only mapping compiles nothing.
    sourceSets {
        listOf("google", "official", "insider").forEach { channel ->
            getByName(channel).kotlin.srcDir("src/gms/java")
            getByName("test${channel.replaceFirstChar { it.uppercase() }}")
                .kotlin.srcDir("src/testGms/java")
        }

        // In-app APK self-update (download service, notifications, installer, their manifest
        // entries). Only channels allowed to install their own APK map it; google opens Play and
        // fdroid opens F-Droid through the UpdateDeliveryModule in src/google/java and src/fdroid/java.
        // manifest.srcFile replaces src/<channel>/AndroidManifest.xml for these two channels.
        listOf("official", "insider").forEach { channel ->
            getByName(channel).kotlin.srcDir("src/sideload/java")
            getByName(channel).manifest.srcFile("src/sideload/AndroidManifest.xml")
            getByName("test${channel.replaceFirstChar { it.uppercase() }}")
                .kotlin.srcDir("src/testSideload/java")
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
            signingConfig = signingConfigs.getByName("release")
        }
    }

    testOptions {
        unitTests.isIncludeAndroidResources = true
        // Gradle's 512m default overflows here: the container reader's cap test declares a frame at
        // MAX_FRAME_PAYLOAD_BYTES (64 MiB) and the reader buffers that length before it can see the
        // frame is truncated. Mirrors :chat / :call / :base.
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
 * Reuses the file-scope resolvedVersionCode / resolvedBuildTimestamp (sampled
 * once at configuration start) so the filename's versionCode is identical to the
 * one baked into the flavor config, and every APK in one build shares the suffix.
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
                "$flavor1-$flavor2-v$appVersionName-$resolvedVersionCode-$resolvedBuildTimestamp-$buildType.apk"
            )
        }
    }
}

roborazzi {
    outputDir.set(rootProject.file("screenshots/app"))
}

// The root project's detekt source set is src/main/** + src/test/** only, so the per-channel
// source trees would go unscanned. Add them here — new production code must be linted.
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
// Firebase Analytics is removed (#1407). Keep it and its measurement runtime from coming back
// transitively. firebase-measurement-connector stays: Crashlytics and Remote Config reference its
// AnalyticsConnector interface at registration, and it collects nothing without Analytics.
configurations.all {
    exclude(group = "com.google.firebase", module = "firebase-analytics")
    exclude(group = "com.google.firebase", module = "firebase-core")
    listOf(
        "play-services-measurement",
        "play-services-measurement-api",
        "play-services-measurement-base",
        "play-services-measurement-impl",
        "play-services-measurement-sdk",
        "play-services-measurement-sdk-api",
    ).forEach { exclude(group = "com.google.android.gms", module = it) }
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

    // P2P transfer engine for cross-device migration (a shipping feature). src/main glue
    // (P2pContainerFusion/Pipe/ReceiveSink, MigrationHandshakeGate) depends on it, so it must be on
    // every variant's classpath. The module's demo/self-test UI stays debug-only via :p2p's own
    // src/debug source set — release gets the engine, not the demo.
    implementation(project(":p2p"))
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

    // Firebase
    implementation(platform(libs.firebase.bom))
    implementation(libs.bundles.firebase)

    // PictureSelector
    implementation(project(":selector"))

    // Hilt
    implementation(libs.hilt.android)
    ksp(libs.hilt.compiler)

    // ML Kit translation, used only by MlKitTranslateBackend under src/gms/java. Declared per
    // channel rather than for the whole module: the fdroid channel binds UnavailableTranslateBackend
    // and must not carry any Google proprietary artifact.
    "googleImplementation"(libs.mlkit.translate)
    "googleImplementation"(libs.mlkit.language.id)
    "officialImplementation"(libs.mlkit.translate)
    "officialImplementation"(libs.mlkit.language.id)
    "insiderImplementation"(libs.mlkit.translate)
    "insiderImplementation"(libs.mlkit.language.id)

    // 其他依赖
    implementation(libs.jwtdecode)
    debugImplementation(libs.leakcanary)
    implementation(libs.play.services.base)
    implementation(libs.reflections)

    // 性能监控
    implementation(libs.anrwatchdog)
    // Pinned to 1.4.1: avoids transitive .module dependency verification failures. Initializer disabled in AndroidManifest (no baseline-prof.txt).
    implementation(libs.profileinstaller)

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
}
