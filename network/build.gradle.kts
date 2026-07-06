import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import java.util.Base64

plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.hilt.android)
    alias(libs.plugins.ksp)
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
    testOptions {
        unitTests.isIncludeAndroidResources = true
    }
    defaultConfig {
        multiDexEnabled = true

        buildConfigField("String", "VERSION_FLAG", "\"${getVersionFlag()}\"")
        buildConfigField("String", "CONFIG_PSK", "\"${getConfigPSK()}\"")
        buildConfigField("String", "CONFIG_URLS", "\"${escapeForJavaString(getConfigUrls())}\"")
        buildConfigField("String", "CONFIG_PUBLIC_KEYS", "\"${escapeForJavaString(getConfigPublicKeys())}\"")
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

fun escapeForJavaString(value: String): String {
    return value
        .replace("\\", "\\\\")
        .replace("\"", "\\\"")
        .replace("\n", "")
        .replace("\r", "")
}

fun getConfigPSK(): String {
    if (properties.contains("CONFIG_PSK")) {
        return properties["CONFIG_PSK"].toString()
    }
    return System.getenv("CONFIG_PSK") ?: ""
}

fun decodeBase64(encoded: String): String {
    return String(Base64.getDecoder().decode(encoded))
}

fun getConfigUrls(): String {
    if (properties.contains("CONFIG_URLS_BASE64")) {
        return properties["CONFIG_URLS_BASE64"].toString()
    }
    val env = System.getenv("CONFIG_URLS_BASE64") ?: return ""
    return if (env.isNotEmpty()) decodeBase64(env) else ""
}

fun getConfigPublicKeys(): String {
    if (properties.contains("CONFIG_ECDSA_KEYS_BASE64")) {
        return properties["CONFIG_ECDSA_KEYS_BASE64"].toString()
    }
    val env = System.getenv("CONFIG_ECDSA_KEYS_BASE64") ?: return ""
    return if (env.isNotEmpty()) decodeBase64(env) else ""
}

dependencies {
    implementation(project(":base"))
    implementation(project(":database"))

    // Hilt
    implementation(libs.hilt.android)
    ksp(libs.hilt.compiler)

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
    kspTest(libs.hilt.compiler)
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