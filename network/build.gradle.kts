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
    var versionFlag = System.getenv("versionFlag")
    if(versionFlag.isNullOrEmpty()){
        versionFlag = "cinnamon"
    }
    return versionFlag
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

    // 测试依赖已通过base模块提供
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