pluginManagement {
    repositories {
        gradlePluginPortal()
        google()
        mavenCentral()
        maven { url = java.net.URI("https://developer.huawei.com/repo/") }
    }
}
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
        maven { url = java.net.URI("https://jitpack.io") }
        maven {
            url = java.net.URI("https://raw.github.com/signalapp/maven/master/sqlcipher/release/")
            content {
                includeGroupByRegex("org\\.signal.*")
            }
        }
        maven { url = java.net.URI("https://developer.huawei.com/repo/") }
//        maven {
//            url = java.net.URI("https://oss.sonatype.org/service/local/staging/deploy/maven2/")
//        }
//        maven {
//            url = java.net.URI("https://oss.sonatype.org/content/repositories/snapshots/")
//        }
        maven {
            url = uri("https://raw.githubusercontent.com/difftim/AndroidRepo/main/")
        }

    }
}
rootProject.name = "difft-android"
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
