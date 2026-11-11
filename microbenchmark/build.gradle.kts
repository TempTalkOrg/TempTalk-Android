import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.androidx.benchmark)
}

android {
    namespace = "com.difft.android.microbenchmark"

    compileSdk = libs.versions.compileSdk.get().toInt()
    
    
    ndkVersion = libs.versions.ndk.get()

    defaultConfig {
        minSdk = libs.versions.minSdk.get().toInt()
        testInstrumentationRunner = "androidx.benchmark.junit4.AndroidBenchmarkRunner"
        
        
        
        testInstrumentationRunnerArguments["androidx.benchmark.suppressErrors"] = "EMULATOR"
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
        
        isCoreLibraryDesugaringEnabled = true
    }

    
    
    
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
    
    
    androidTestImplementation(libs.kotlinx.coroutines.android)
    androidTestImplementation(project(":database"))
    androidTestImplementation(testFixtures(project(":database")))
    
    
    
    androidTestImplementation(project(":network"))
    androidTestImplementation(project(":base"))
    androidTestImplementation(libs.dtproto) {
        
        
        exclude(group = "net.java.dev.jna", module = "jna")
    }
    androidTestImplementation(libs.signal.android)
    coreLibraryDesugaring(libs.desugar.jdk.libs)
    
    
    
}
