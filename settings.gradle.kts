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
        maven { url = uri("https://jitpack.io") }
        maven {
            url = uri("https://raw.github.com/signalapp/maven/master/sqlcipher/release/")
            content {
                includeGroupByRegex("org\\.signal.*")
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
