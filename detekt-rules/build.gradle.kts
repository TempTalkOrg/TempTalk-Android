// kotlin("jvm") (not kotlin-android), and without an explicit version:
//   - Pure JVM keeps this module Android-dep-free (Detekt rule-module convention).
//   - kotlin-android already puts the kotlin-gradle-plugin on classpath; adding a
//     version here would trigger a "plugin already on classpath with unknown
//     version" conflict on fresh clones.
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
    compileOnly(libs.detekt.api)
    // TODO(#723 follow-up): re-enable unit tests once Detekt 2.0 stable lands
    //   (or our Kotlin pin moves off 2.3.0). Currently detekt-test 2.0.0-alpha.3
    //   ships against kotlin-compiler 2.3.21 internal ABI (StandaloneProjectFactory
    //   → KotlinCoreEnvironment.getOrCreateApplicationEnvironment 3-arg overload)
    //   that doesn't exist in our pinned 2.3.0 nor in the upstream 2.3.21 build
    //   we tried. Until the API stabilizes, validation lives in:
    //     - Real-project full-scan (./gradlew detekt) — see issue #723 PR description
    //     - Manual inspection of baseline.xml entries vs the issue triage table
}

tasks.named("test") {
    enabled = false
    doFirst {
        logger.lifecycle("Detekt rule unit tests intentionally disabled; see build.gradle.kts TODO.")
    }
}

tasks.test {
    useJUnit()
}
