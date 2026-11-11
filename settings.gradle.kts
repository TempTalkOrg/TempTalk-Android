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
            
            
            
            
            
            
            metadataSources {
                gradleMetadata()
                mavenPom()
                ignoreGradleMetadataRedirection()
            }
        }
    }
}



providers.environmentVariable("GRADLE_REMOTE_CACHE_URL").orNull
    ?.takeIf { it.isNotBlank() }?.let { cacheUrl ->
        val endpoint = uri(cacheUrl)
        require(endpoint.userInfo == null && endpoint.query == null && endpoint.fragment == null) {
            "Remote build cache URL must not contain credentials, query or fragment"
        }
        require(endpoint.scheme == "https" ||
            (endpoint.scheme == "http" && endpoint.host == "127.0.0.1")) {
            "Remote build cache must use HTTPS or explicit IPv4 loopback HTTP"
        }
        buildCache {
            remote<HttpBuildCache> {
                url = endpoint
                isPush = providers.environmentVariable("GRADLE_REMOTE_CACHE_PUSH").orNull == "true"
                isAllowInsecureProtocol = endpoint.scheme == "http" && endpoint.host == "127.0.0.1"
                val user = providers.environmentVariable("GRADLE_REMOTE_CACHE_USER").orNull
                val password = providers.environmentVariable("GRADLE_REMOTE_CACHE_PASSWORD").orNull
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
