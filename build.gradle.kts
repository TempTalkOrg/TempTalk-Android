buildscript {
    dependencies {
        // Force the build/plugin classpath's commons-lang3 to the patched
        // version. AGP 9 / Gradle build tooling pulls commons-lang3 3.16.0
        // transitively via commons-compress 1.27.1, which carries
        // CVE-2025-48924 (uncontrolled recursion, HIGH). Gradle conflict
        // resolution on the merged buildscript classpath picks the higher
        // version, so this bumps the plugin transitive to 3.18.0 (the fix).
        // Build-time only — not on any app runtime classpath — but the dep
        // security scan treats build-time deps as in-scope (plugins run with
        // full machine permissions).
        classpath("org.apache.commons:commons-lang3:3.18.0")
    }
}

plugins {
    alias(libs.plugins.hilt.android) apply false
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.android.library) apply false
    alias(libs.plugins.ksp) apply false
    alias(libs.plugins.kotlin.compose) apply false
    alias(libs.plugins.google.services) apply false
    alias(libs.plugins.firebase.crashlytics) apply false
    alias(libs.plugins.firebase.perf) apply false

    alias(libs.plugins.navigation.safeargs) apply false
    alias(libs.plugins.protobuf.plugin) apply false
    alias(libs.plugins.kotlin.serialization) apply false
    alias(libs.plugins.roborazzi) apply false
    alias(libs.plugins.detekt) apply false
}

// Detekt — apply to all subprojects EXCEPT :detekt-rules itself.
// :detekt-rules ships the custom rules and does not need to scan itself.
//
// Lint — every Android subproject gets the :lintchecks custom-rules jar.
// The lint {} configuration is scoped via `plugins.withId(...)` so the android
// extension is only touched on subprojects that actually apply an Android
// plugin (`:detekt-rules` / `:lintchecks` are pure JVM and have no `android`
// block).
subprojects {
    if (name == "detekt-rules" || name == "lintchecks") return@subprojects
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

    // Gradle 9 defaults `failOnNoDiscoveredTests` to true: a test task with test
    // sources present but zero discovered @Test methods now fails the build.
    // Several modules legitimately have no unit tests (e.g. :login ships only a
    // robolectric.properties under src/test). Restore the Gradle 8 behaviour
    // (no tests = pass). This only affects the zero-discovered-tests case;
    // modules with real tests still discover and run them normally.
    tasks.withType<org.gradle.api.tasks.testing.Test>().configureEach {
        failOnNoDiscoveredTests = false
    }

    // Lint — scope to subprojects that apply an Android plugin.
    // The `lintChecks` configuration is registered by AGP only AFTER its
    // plugin is applied — adding `lintChecks(project(":lintchecks"))` at
    // subprojects {} top-level fails for any subproject whose own
    // build.gradle.kts hasn't run yet. Hence wrap both the lintChecks deps
    // wiring AND the lint {} block inside plugins.withId(...) callbacks.
    //
    //   abortOnError → fail PR CI (lintTTDevOfficialDebug etc.) on any new
    //                  baseline-escaping violation (custom :lintchecks issues
    //                  AND AGP built-in rules).
    //   lint.baseline → per-module `lint-baseline.xml` absorbs pre-existing
    //                  built-in violations; only NEW violations fail CI.
    //                  Regenerate via `./gradlew updateLintBaseline`.
    //   checkReleaseBuilds = false → release CI (tt_official / tt_beta / ...)
    //                does NOT run lint, so a lint failure cannot block the
    //                remote release build pipeline. Enforcement lives entirely
    //                in PR CI + IDE real-time analysis.
    //   ignoreTestSources → unit-test code may legitimately use `println` for
    //                observability while iterating on a flow/coroutine test.
    plugins.withId("com.android.application") {
        dependencies { add("lintChecks", project(":lintchecks")) }
        extensions.configure<com.android.build.api.dsl.ApplicationExtension>("android") {
            // Per-module baseline. file() resolves to the subproject's projectDir.
            // Regenerate via `./gradlew updateLintBaseline`.
            lint.baseline = file("lint-baseline.xml")
            configureLint(lint)
        }
    }
    plugins.withId("com.android.library") {
        dependencies { add("lintChecks", project(":lintchecks")) }
        extensions.configure<com.android.build.api.dsl.LibraryExtension>("android") {
            lint.baseline = file("lint-baseline.xml")
            configureLint(lint)
        }
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
            // Netty reaches this build only through AGP's Unified Test Platform
            // configurations (io.grpc:grpc-netty), never the app runtime classpath
            // — but the dep security scan treats test/build tooling as in-scope.
            if (requested.group == "io.netty") {
                useVersion("4.1.136.Final")
                because("Fix CVE: Bzip2Decoder infinite loop (CVE-2026-59901), SPDY unbounded settings map / zlib expansion / RST_STREAM leak (CVE-2026-55831/55833/56745), HttpContentEncoder queue growth (CVE-2026-59899), CORS bypass (CVE-2026-56746), WebSocket V07/V08 handshake validation (CVE-2026-59898), multipart CRLF injection (CVE-2026-59921), HTTP/2 Host header dedup (CVE-2026-59900) — plus the 4.1.135 fixes for IpSubnetFilter bypass (CVE-2026-44249), SNIHandler/HTTP2 DoS (CVE-2026-45416/50560), FD leak (CVE-2026-45536), hostname verification bypass (CVE-2026-50010)")
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
            // Build-tooling transitive (androidLintTool + Unified Test Platform
            // configs) pulls commons-lang3 3.16.0 via commons-compress 1.27.1
            // (com.android.tools 32.2.0). Carries CVE-2025-48924 (uncontrolled
            // recursion, HIGH). Not on any app runtime classpath, but the dep
            // security scan treats build-time deps as in-scope. The buildscript
            // classpath is patched separately in the top-level buildscript {}.
            if (requested.group == "org.apache.commons" && requested.name == "commons-lang3") {
                useVersion("3.18.0")
                because("Fix CVE-2025-48924: Uncontrolled Recursion in commons-lang3 3.16.0")
            }
            // Same build-tooling configs pull httpclient 4.5.6 via httpmime 4.5.6
            // (com.android.tools:sdklib 32.2.0). Carries CVE-2020-13956 (improper
            // input validation / URI authority mis-parsing, MEDIUM). Build-time only.
            if (requested.group == "org.apache.httpcomponents" && requested.name == "httpclient") {
                useVersion("4.5.14")
                because("Fix CVE-2020-13956: Improper Input Validation in httpclient 4.5.6")
            }
        }
    }
}

/**
 * Fails the build if any source / config file contains the literal string
 * `STOPSHIP`. Convention (borrowed from Signal-Android) for marking work-in-
 * progress code that MUST NOT reach a release branch — sentinels are added
 * during local iteration and the developer relies on this task to catch any
 * sentinels left behind before merge.
 *
 * Wired into the default `check` lifecycle (and therefore PR CI) below.
 *
 * Scope: source / build-script / resource files inside each module's `src/`
 * tree. Tooling directories (.claude/, tmp/, docs/, maestro/, scripts/)
 * are excluded so docs and tooling can mention "STOPSHIP" by name without
 * triggering the rule. lint.xml is excluded for the same reason (this file
 * documents the rule).
 */
tasks.register("checkStopship") {
    description = "Fail the build if any source file contains the literal 'STOPSHIP'."
    group = "verification"

    val cachedProjectDir = projectDir
    doLast {
        // toml + properties cover gradle/libs.versions.toml and *.properties
        // (gradle/local.properties is gitignored but other .properties files
        // are checked in); both can host hand-edited STOPSHIP sentinels.
        val allowedExtensions = setOf("kt", "kts", "java", "xml", "toml", "properties")
        // Excluded files are matched by relative path (NOT basename) so that
        // module-level build.gradle.kts (app/, chat/, ...) remain scanned.
        // The ROOT build.gradle.kts defines the rule + error message and
        // therefore contains the literal "STOPSHIP"; lint.xml may reference
        // it in comments — these two files are the only ones exempted.
        val excludedFiles = setOf("build.gradle.kts", "lint.xml")
        val excludedDirectories = setOf(
            ".idea",
            ".gradle",
            ".claude",
            "tmp",
            "docs",
            "maestro",
            "scripts",
        )

        val offenders = cachedProjectDir.walkTopDown()
            // Skip any directory named `build` (Gradle output) at any nesting
            // depth, and any tooling/docs directory we don't want to scan.
            // The src/ tree never contains a `build/` subdir we care about
            // — earlier `dir.relativeTo(cachedProjectDir).path.contains("src")`
            // exception was inherited from Signal but is unrealizable here.
            .onEnter { dir ->
                val name = dir.name
                name != "build" && name !in excludedDirectories
            }
            .filter { it.isFile && it.extension in allowedExtensions }
            .filter { it.relativeTo(cachedProjectDir).path !in excludedFiles }
            .filter { runCatching { it.readText().contains("STOPSHIP") }.getOrDefault(false) }
            .map { it.relativeTo(cachedProjectDir).path }
            .toList()

        logger.lifecycle("[checkStopship] scanned, ${offenders.size} offender(s)")
        if (offenders.isNotEmpty()) {
            throw GradleException(
                "STOPSHIP markers found — must be removed before merge:\n" +
                    offenders.joinToString("\n") { "  - $it" }
            )
        }
    }
}

// Note: `checkStopship` is invoked explicitly by the lint-pr.yml workflow
// (commit 7) rather than hooked into a Gradle lifecycle task. The root
// project has no `check` task to hook into, and wiring it via
// `allprojects { tasks.matching("check") { ... } }` would multiply the
// scan across every module needlessly. PR CI runs it once explicitly.

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

/**
 * Configures Android Lint for a subproject that applies an Android plugin.
 *
 * Invoked from `plugins.withId(...)` blocks so the `android` extension is
 * guaranteed to be registered (avoids "extension not found" during early
 * configuration). Shared across ApplicationExtension and LibraryExtension via
 * the common `Lint` DSL type.
 *
 * AGP's full built-in rule set runs in addition to the :lintchecks custom
 * issues. Existing violations are absorbed by per-module
 * `lint-baseline.xml`; only new (baseline-escaping) violations fail the PR.
 *
 * Adding new :lintchecks detectors → register in
 * `lintchecks/src/main/java/com/difft/android/lint/Registry.kt`. The
 * detector will activate automatically (no `checkOnly` whitelist gate).
 */
fun configureLint(lint: com.android.build.api.dsl.Lint) {
    lint.abortOnError = true
    lint.checkReleaseBuilds = false
    lint.ignoreTestSources = true
    lint.warningsAsErrors = false
    lint.htmlReport = true
    lint.xmlReport = false
    lint.sarifReport = false
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