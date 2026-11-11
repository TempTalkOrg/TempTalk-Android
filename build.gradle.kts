buildscript {
    dependencies {
        
        
        classpath("org.apache.commons:commons-lang3:3.18.0")
    }
}

plugins {
    alias(libs.plugins.hilt.android) apply false
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.android.library) apply false
    alias(libs.plugins.ksp) apply false
    alias(libs.plugins.kotlin.compose) apply false

    alias(libs.plugins.navigation.safeargs) apply false
    alias(libs.plugins.protobuf.plugin) apply false
    alias(libs.plugins.kotlin.serialization) apply false
    alias(libs.plugins.roborazzi) apply false
    alias(libs.plugins.detekt) apply false
    alias(libs.plugins.androidx.benchmark) apply false
}



subprojects {
    if (name == "detekt-rules" || name == "lintchecks") return@subprojects
    apply(plugin = "dev.detekt")

    extensions.configure<dev.detekt.gradle.extensions.DetektExtension> {
        config.setFrom(rootProject.files("config/detekt/detekt.yml"))
        
        baseline = file("config/detekt/baseline.xml")
        buildUponDefaultConfig = true
        autoCorrect = false
        parallel = true
        basePath.set(rootProject.layout.projectDirectory)
        
        
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

    
    tasks.withType<org.gradle.api.tasks.testing.Test>().configureEach {
        failOnNoDiscoveredTests = false
    }

    
    
    
    plugins.withId("com.android.application") {
        dependencies { add("lintChecks", project(":lintchecks")) }
        extensions.configure<com.android.build.api.dsl.ApplicationExtension>("android") {
            
            
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

    
    tasks.withType<dev.detekt.gradle.Detekt>().configureEach {
        reports {
            sarif.required.set(true)
            html.required.set(true)
            checkstyle.required.set(false)
            markdown.required.set(false)
        }
        jvmTarget.set(libs.versions.jvmTarget.get())
        
        exclude("**/build/**", "**/generated/**", "**/resources/**")
    }
    tasks.withType<dev.detekt.gradle.DetektCreateBaselineTask>().configureEach {
        jvmTarget.set(libs.versions.jvmTarget.get())
    }
}

allprojects {
    configurations.all {
        
        
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
                useVersion("4.1.136.Final")
                because("Fix CVE: Bzip2Decoder infinite loop (CVE-2026-59901), SPDY unbounded settings map / zlib expansion / RST_STREAM leak (CVE-2026-55831/55833/56745), HttpContentEncoder queue growth (CVE-2026-59899), CORS bypass (CVE-2026-56746), WebSocket V07/V08 handshake validation (CVE-2026-59898), multipart CRLF injection (CVE-2026-59921), HTTP/2 Host header dedup (CVE-2026-59900) — plus the 4.1.135 fixes for IpSubnetFilter bypass (CVE-2026-44249), SNIHandler/HTTP2 DoS (CVE-2026-45416/50560), FD leak (CVE-2026-45536), hostname verification bypass (CVE-2026-50010)")
            }
            if (requested.group == "org.bouncycastle" &&
                (requested.name == "bcprov-jdk18on" ||
                    requested.name == "bcpkix-jdk18on" ||
                    requested.name == "bcutil-jdk18on")) {
                useVersion("1.85")
                because("Fix CVE: cert validation bypass (CVE-2026-8763/58062), input validation (CVE-2026-59650), signature verification bypass (CVE-2026-12860/59639), integrity check validation (CVE-2026-12802/12803/12816/58061/59642), oversized memory allocation (CVE-2026-12185/14682/58060), uncontrolled recursion (CVE-2026-13506/59645), inadequate encryption strength (CVE-2026-59651), algorithmic complexity DoS (CVE-2026-58059), unbounded resource allocation (CVE-2026-13586/15055/58063/59647) — plus the 1.84 fixes for Broken Crypto Algorithm, Timing Attack, LDAP Injection")
            }
            
            
            
            
            val isUtpConfig = name.startsWith("unified-test-platform")
            if (!isUtpConfig &&
                requested.group == "com.google.protobuf" &&
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
            
            
            if (requested.group == "org.apache.commons" && requested.name == "commons-lang3") {
                useVersion("3.18.0")
                because("Fix CVE-2025-48924: Uncontrolled Recursion in commons-lang3 3.16.0")
            }
            
            
            
            if (requested.group == "org.apache.httpcomponents" && requested.name == "httpclient") {
                useVersion("4.5.14")
                because("Fix CVE-2020-13956: Improper Input Validation in httpclient 4.5.6")
            }
        }
    }
}






tasks.register("checkStopship") {
    description = "Fail the build if any source file contains the literal 'STOPSHIP'."
    group = "verification"

    val cachedProjectDir = projectDir
    doLast {
        
        
        
        val allowedExtensions = setOf("kt", "kts", "java", "xml", "toml", "properties")
        
        
        
        
        
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



tasks.register("testAll") {
    description = "Run unit tests for all modules (single variant per flavored module)"
    group = "verification"
    dependsOn(
        
        ":base:testDebugUnitTest",
        ":network:testDebugUnitTest",
        ":database:testDebugUnitTest",
        ":chat:testDebugUnitTest",
        ":video:testDebugUnitTest",
        ":image-editor:testDebugUnitTest",
        ":security:testDebugUnitTest",
        ":call:testDebugUnitTest",
        ":selector:testDebugUnitTest",
        
        ":app:testTTDevOfficialDebugUnitTest",
        ":login:testTTDevDebugUnitTest",
        
        ":lintchecks:test",
    )
}







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