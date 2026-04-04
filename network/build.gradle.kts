import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.hilt.android)
    alias(libs.plugins.kotlin.kapt)
    id("com.google.protobuf")
}

android {
    namespace = "com.difft.android.network"

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

    testOptions {
        unitTests.isIncludeAndroidResources = true
    }
    defaultConfig {
        multiDexEnabled = true

        buildConfigField("String", "VERSION_FLAG", "\"${getVersionFlag()}\"")
    }
    buildFeatures {
        buildConfig = true
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.fromTarget(libs.versions.jvmTarget.get()))
    }
}

hilt {
    enableAggregatingTask = true
}

fun getVersionFlag(): String {
    if (properties.contains("versionFlag")) {
        return properties["versionFlag"].toString()
    }
    val envValue = System.getenv("versionFlag")
    if (!envValue.isNullOrEmpty()) {
        return envValue
    }
    return "cinnamon"
}

dependencies {
    implementation(project(":base"))
    implementation(project(":database"))

    // Hilt
    implementation(libs.hilt.android)
    kapt(libs.hilt.compiler)
    kapt(libs.kotlin.metadata.jvm)

    // Network specific dependencies
    implementation(libs.okhttp.logging.interceptor)

    // JWT
    implementation(libs.jwtdecode)

    // Protobuf
    implementation(libs.protobuf.kotlin.lite)
    implementation(libs.bundles.jackson)
    implementation(libs.signal.android)

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
}
protobuf {
    protoc {
        // The artifact spec of the Protobuf Compiler
        artifact = "com.google.protobuf:protoc:3.21.2"
    }
    generateProtoTasks {
        all().forEach {
            it.builtins {
                register("java") {
                    option("lite")
                }
                register("kotlin") {
                    option("lite")
                }
            }
        }
    }
}