plugins {
    id("com.google.dagger.hilt.android") version "2.49" apply false
    id("com.android.application") version "8.7.2" apply false
    id("com.android.library") version "8.7.2" apply false
    id("org.jetbrains.kotlin.android") version "1.9.23" apply false
    id("org.jetbrains.kotlin.kapt") version "1.9.23" apply false
    id("com.google.gms.google-services") version "4.4.0" apply false
    id("com.google.firebase.crashlytics") version "2.9.9" apply false
    id("com.google.firebase.firebase-perf") version "1.4.2" apply false

    // 其他插件
    id("androidx.navigation.safeargs.kotlin") version "2.8.4" apply false
    id("com.google.protobuf") version "0.9.4" apply false
    id("org.jetbrains.kotlin.plugin.serialization") version "1.9.23" apply false
}

// 强制所有子项目使用指定的Kotlin版本
allprojects {
    configurations.all {
        resolutionStrategy.eachDependency {
            if (requested.group == "org.jetbrains.kotlin") {
                useVersion("1.9.23")
                because("Force Kotlin version to 1.9.23 for Compose compatibility")
            }
        }
    }
}