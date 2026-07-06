// :lintchecks — custom Android Lint rules for TempTalk.
//
// Why kotlin("jvm") (not kotlin-android):
//   - Lint custom-rules convention: pure JVM module, no Android dependencies.
//   - Plugin classpath stays clean (matches :detekt-rules pattern).
//
// The compiled jar is consumed via `lintChecks(project(":lintchecks"))` from
// each subproject — wired in the root build.gradle.kts `subprojects {}` block.
plugins {
    kotlin("jvm")
}

java {
    sourceCompatibility = JavaVersion.toVersion(libs.versions.jvmTarget.get())
    targetCompatibility = JavaVersion.toVersion(libs.versions.jvmTarget.get())
}

kotlin {
    jvmToolchain(libs.versions.jvmTarget.get().toInt())
}

dependencies {
    compileOnly(libs.lint.api)
    compileOnly(libs.lint.checks)

    // Lint test framework — stable API, can be enabled now (unlike :detekt-rules
    // which is blocked by 2.0-alpha ABI churn). Kept for follow-up when we
    // start adding more detectors; not used in the initial 6-detector set.
    testImplementation(libs.lint.tests)
    testImplementation(libs.lint.api)
    testImplementation(libs.junit)
}

tasks.jar {
    manifest {
        // SPI fallback — `META-INF/services/...IssueRegistry` is the modern
        // way, but `Lint-Registry-v2` jar attribute is also honored. Declaring
        // both is the convention in upstream lint-checks samples.
        attributes(
            "Lint-Registry-v2" to "com.difft.android.lint.Registry"
        )
    }
}
