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
    kotlinOptions {
        jvmTarget = libs.versions.jvmTarget.get()
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

    // Network specific dependencies
    implementation(libs.okhttp.logging.interceptor)
    implementation(libs.bouncycastle)
    implementation(libs.okhttp.tls)
    implementation(libs.dnsjava)

    // JWT
    implementation(libs.jwtdecode)

    // Protobuf
    implementation(libs.protobuf.kotlin.lite)
    implementation(libs.bundles.jackson)
    implementation(libs.signal.client)

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