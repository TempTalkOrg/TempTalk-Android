plugins {
    alias(libs.plugins.hilt.android) apply false
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.android.library) apply false
    alias(libs.plugins.kotlin.android) apply false
    alias(libs.plugins.kotlin.kapt) apply false
    alias(libs.plugins.kotlin.compose) apply false
    alias(libs.plugins.google.services) apply false
    alias(libs.plugins.firebase.crashlytics) apply false
    alias(libs.plugins.firebase.perf) apply false

    alias(libs.plugins.navigation.safeargs) apply false
    alias(libs.plugins.protobuf.plugin) apply false
    alias(libs.plugins.kotlin.serialization) apply false
    alias(libs.plugins.roborazzi) apply false
}

allprojects {
    configurations.all {
        resolutionStrategy.eachDependency {
            if (requested.group == "org.jetbrains.kotlin") {
                useVersion("2.3.0")
                because("Force Kotlin version to 2.3.0 for Coroutines 1.9.0 compatibility")
            }
            if (requested.group == "io.netty") {
                useVersion("4.1.133.Final")
                because("Fix CVE: HTTP Request Smuggling, DoS, Data Amplification, CRLF Injection")
            }
            if (requested.group == "org.bouncycastle" &&
                (requested.name == "bcprov-jdk18on" ||
                    requested.name == "bcpkix-jdk18on" ||
                    requested.name == "bcutil-jdk18on")) {
                useVersion("1.84")
                because("Fix CVE: Broken Crypto Algorithm, Timing Attack, LDAP Injection")
            }
            if (requested.group == "com.google.protobuf" &&
                (requested.name == "protobuf-java" ||
                    requested.name == "protobuf-javalite" ||
                    requested.name == "protobuf-kotlin-lite")) {
                useVersion("3.25.5")
                because("Fix CVE-2024-7254: Stack-based Buffer Overflow")
            }
            if (requested.group == "com.google.guava" && requested.name == "guava") {
                useVersion("33.4.0-android")
                because("Fix CVE-2023-2976: Creation of Temporary File with Insecure Permissions")
            }
            if (requested.group == "junit" && requested.name == "junit") {
                useVersion("4.13.2")
                because("Fix Information Exposure vulnerability in junit 4.12")
            }
        }
    }
}

tasks.register("testAll") {
    description = "Run unit tests for all modules (single variant per flavored module)"
    group = "verification"
    dependsOn(
        // Non-flavored modules
        ":base:testDebugUnitTest",
        ":network:testDebugUnitTest",
        ":database:testDebugUnitTest",
        ":chat:testDebugUnitTest",
        ":video:testDebugUnitTest",
        ":image-editor:testDebugUnitTest",
        ":security:testDebugUnitTest",
        ":call:testDebugUnitTest",
        ":selector:testDebugUnitTest",
        // Flavored modules
        ":app:testTTDevOfficialDebugUnitTest",
        ":login:testTTDevDebugUnitTest",
    )
}

tasks.register("verifyScreenshots") {
    description = "Verify Roborazzi screenshots against baselines for all Compose modules"
    group = "verification"
    dependsOn(
        ":app:verifyRoborazziTTDevOfficialDebug",
        ":base:verifyRoborazziDebug",
        ":chat:verifyRoborazziDebug",
        ":call:verifyRoborazziDebug",
        ":login:verifyRoborazziTTDevDebug",
    )
}

tasks.register("recordScreenshots") {
    description = "Record Roborazzi screenshot baselines for all Compose modules"
    group = "verification"
    dependsOn(
        ":app:recordRoborazziTTDevOfficialDebug",
        ":base:recordRoborazziDebug",
        ":chat:recordRoborazziDebug",
        ":call:recordRoborazziDebug",
        ":login:recordRoborazziTTDevDebug",
    )
}