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
// Shared remote build cache, opt-in through the environment so a checkout without it behaves
// exactly as before (local cache only). CI runners on the shared build Mac set
// GRADLE_REMOTE_CACHE_URL to the on-host HTTP cache; only CI pushes, everyone else reads. A cache
// that is unreachable degrades to a Gradle warning and a normal build, never a failure.
System.getenv("GRADLE_REMOTE_CACHE_URL")?.takeIf { it.isNotBlank() }?.let { cacheUrl ->
    buildCache {
        remote<HttpBuildCache> {
            url = uri(cacheUrl)
            isPush = System.getenv("CI") == "true"
            isAllowInsecureProtocol = cacheUrl.startsWith("http://")
            val user = System.getenv("GRADLE_REMOTE_CACHE_USER")
            val password = System.getenv("GRADLE_REMOTE_CACHE_PASSWORD")
            if (!user.isNullOrBlank() && !password.isNullOrBlank()) {
                credentials {
                    username = user
                    this.password = password
                }
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
