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

// 强制所有子项目使用指定的Kotlin版本
allprojects {
    configurations.all {
        resolutionStrategy.eachDependency {
            if (requested.group == "org.jetbrains.kotlin" ) {
                useVersion("2.3.0")
                because("Force Kotlin version to 2.3.0 for Coroutines 1.9.0 compatibility")
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