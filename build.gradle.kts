plugins {
    alias(libs.plugins.hilt.android) apply false
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.android.library) apply false
    alias(libs.plugins.kotlin.android) apply false
    alias(libs.plugins.kotlin.kapt) apply false
    alias(libs.plugins.kotlin.compose) apply false

    alias(libs.plugins.navigation.safeargs) apply false
    alias(libs.plugins.protobuf.plugin) apply false
    alias(libs.plugins.kotlin.serialization) apply false
    alias(libs.plugins.roborazzi) apply false
    alias(libs.plugins.detekt) apply false
}

// Detekt — apply to all subprojects EXCEPT :detekt-rules itself.
// :detekt-rules ships the custom rules and does not need to scan itself.
subprojects {
    if (name == "detekt-rules") return@subprojects
    apply(plugin = "dev.detekt")

    extensions.configure<dev.detekt.gradle.extensions.DetektExtension> {
        config.setFrom(rootProject.files("config/detekt/detekt.yml"))
        // Per-module baseline — Detekt's multi-module Gradle plugin can't
        // share a single baseline file (each :module:detektBaseline overwrites
        // the same file). Per-module baselines keep regen idempotent and
        // surface violations cleanly in their owning module.
        baseline = file("config/detekt/baseline.xml")
        buildUponDefaultConfig = true
        autoCorrect = false
        parallel = true
        basePath.set(rootProject.layout.projectDirectory)
        // Skip generated sources (Hilt, KSP, navigation safeargs, etc.) and build dirs.
        // Custom source set picks main + test kotlin/java only.
        source.setFrom(
            files(
                "src/main/kotlin",
                "src/main/java",
                "src/test/kotlin",
                "src/test/java",
            )
        )
    }

    dependencies {
        add("detektPlugins", project(":detekt-rules"))
    }

    // SARIF report enables GitHub Code Scanning annotations on PRs.
    tasks.withType<dev.detekt.gradle.Detekt>().configureEach {
        reports {
            sarif.required.set(true)
            html.required.set(true)
            checkstyle.required.set(false)
            markdown.required.set(false)
        }
        jvmTarget.set(libs.versions.jvmTarget.get())
        // Exclude generated and build outputs explicitly (defense in depth).
        exclude("**/build/**", "**/generated/**", "**/resources/**")
    }
    tasks.withType<dev.detekt.gradle.DetektCreateBaselineTask>().configureEach {
        jvmTarget.set(libs.versions.jvmTarget.get())
    }
}

allprojects {
    configurations.all {
        // Detekt 2.0.0-alpha.3 was compiled against Kotlin 2.3.21 stdlib; its
        // classpath needs matching stdlib at runtime. The general 2.3.0 force
        // below would break Detekt with "compiled with 2.3.21 but running with 2.3.0".
        // Scope the Detekt configurations to 2.3.21 and let everything else pin to 2.3.0.
        val isDetektConfig = name.startsWith("detekt") || name.contains("Detekt")
        resolutionStrategy.eachDependency {
            if (requested.group == "org.jetbrains.kotlin") {
                if (isDetektConfig) {
                    useVersion("2.3.21")
                    because("Detekt 2.0.0-alpha.3 was compiled against Kotlin 2.3.21")
                } else {
                    useVersion("2.3.0")
                    because("Force Kotlin version to 2.3.0 for Coroutines 1.9.0 compatibility")
                }
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