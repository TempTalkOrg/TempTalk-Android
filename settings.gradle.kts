pluginManagement {
    repositories {
        gradlePluginPortal()
        google()
        mavenCentral()
    }
}
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
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
include(":database")
include(":detekt-rules")
include(":lintchecks")
