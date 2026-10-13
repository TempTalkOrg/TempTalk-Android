pluginManagement {
    repositories {
        gradlePluginPortal()
        google()
        // Cloud sessions share one egress IP that Maven Central rate-limits (HTTP 429). Gradle stops
        // at the first repository that keeps failing and queries every repository for version
        // ranges, so there the Google mirror replaces mavenCentral() outright. Local and CI are unchanged.
        if (System.getenv("CLAUDE_CODE_REMOTE") == "true") {
            maven { url = uri("https://maven-central.storage-download.googleapis.com/maven2/") }
        } else {
            mavenCentral()
        }
    }
}
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        if (System.getenv("CLAUDE_CODE_REMOTE") == "true") { // see pluginManagement above
            maven { url = uri("https://maven-central.storage-download.googleapis.com/maven2/") }
        } else {
            mavenCentral()
        }
        maven {
            url = uri("https://jitpack.io")
            // JitPack reliably serves Gradle Module Metadata (.module) for this org's
            // multi-module artifacts (e.g. livekit-client-sdk-android sub-modules
            // livekit-android-camerax / ttsignal) but its on-demand .pom generation for
            // those sub-modules frequently times out. Resolve straight from .module and
            // skip the .pom redirect probe; dependency verification still pins .module/.aar
            // SHA-256 in verification-metadata.xml.
            metadataSources {
                gradleMetadata()
                mavenPom()
                ignoreGradleMetadataRedirection()
            }
        }
    }
}
include(":app")
include(":network")
include(":login")
include(":chat")
include(":base")
include(":video")
include(":image-editor")
include(":call")
include(":selector")
include(":security")
include(":p2p")
include(":database")
include(":detekt-rules")
include(":lintchecks")
include(":microbenchmark")
