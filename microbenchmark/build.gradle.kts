import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.androidx.benchmark)
}

android {
    namespace = "com.difft.android.microbenchmark"

    compileSdk = libs.versions.compileSdk.get().toInt()
    // benchmark-common ships libbenchmarkNative.so; AGP 9 dropped the implicit NDK
    // fallback used for llvm-strip (see libs.versions.toml), so pin like :app does.
    ndkVersion = libs.versions.ndk.get()

    defaultConfig {
        minSdk = libs.versions.minSdk.get().toInt()
        testInstrumentationRunner = "androidx.benchmark.junit4.AndroidBenchmarkRunner"
        // Suppresses ONLY the EMULATOR configuration check; suppressed results carry a
        // permanent "EMULATOR_" metric-name prefix as provenance. Real-device runs are
        // unaffected. All other benchmark configuration errors stay hard failures.
        testInstrumentationRunnerArguments["androidx.benchmark.suppressErrors"] = "EMULATOR"
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
        // Required by libsignal-android (AAR metadata check) for the pipeline benchmarks.
        isCoreLibraryDesugaringEnabled = true
    }

    // Correctness requirement, not convention: :database gates setCipherKey on
    // !BuildConfig.DEBUG, so only the release test build measures the encrypted DB
    // (a debug benchmark would silently measure unencrypted SQLite).
    testBuildType = "release"
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.fromTarget(libs.versions.jvmTarget.get()))
    }
}

dependencies {
    androidTestImplementation(libs.junit)
    androidTestImplementation(libs.androidx.test.ext.junit)
    androidTestImplementation(libs.androidx.benchmark.junit4)
    // :database exposes :base via implementation, so CoroutineScope (WCDB's second
    // constructor parameter) is not transitive — declare coroutines directly.
    androidTestImplementation(libs.kotlinx.coroutines.android)
    androidTestImplementation(project(":database"))
    androidTestImplementation(testFixtures(project(":database")))
    // Pipeline-stage benchmarks (issue #1166 L2/L3): proto classes + padding util live in
    // :network, Base64 in :base, DtProto FFI + libsignal key generation for the
    // self-contained encrypt/decrypt fixture. All are existing production dependencies.
    androidTestImplementation(project(":network"))
    androidTestImplementation(project(":base"))
    androidTestImplementation(libs.dtproto) {
        // Same shape as :base's declaration: the jna JAR variant conflicts with the
        // jna AAR already on the runtime classpath.
        exclude(group = "net.java.dev.jna", module = "jna")
    }
    androidTestImplementation(libs.signal.android)
    coreLibraryDesugaring(libs.desugar.jdk.libs)
    // Do NOT add testFixtures(project(":base")): its testFixturesImplementation set
    // includes MockK + Robolectric, which land on the androidTest runtime classpath
    // and inflate the test APK from ~48 MB to ~217 MB.
}
