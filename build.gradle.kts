plugins {
    id("com.google.dagger.hilt.android") version "2.58" apply false
    id("com.android.application") version "8.13.2" apply false
    id("com.android.library") version "8.13.2" apply false
    id("org.jetbrains.kotlin.android") version "2.3.0" apply false
    id("org.jetbrains.kotlin.kapt") version "2.3.0" apply false
    id("org.jetbrains.kotlin.plugin.compose") version "2.3.0" apply false
    id("com.google.gms.google-services") version "4.4.0" apply false
    id("com.google.firebase.crashlytics") version "2.9.9" apply false
    id("com.google.firebase.firebase-perf") version "1.4.2" apply false

    // 其他插件
    id("androidx.navigation.safeargs.kotlin") version "2.9.7" apply false
    id("com.google.protobuf") version "0.9.6" apply false
    id("org.jetbrains.kotlin.plugin.serialization") version "2.3.0" apply false
}

// 强制所有子项目使用指定的Kotlin版本
allprojects {
    configurations.all {
        resolutionStrategy.eachDependency {
            if (requested.group == "org.jetbrains.kotlin" ) {
                useVersion("2.3.0")
                because("Force Kotlin version to 2.3.0 for Coroutines 1.9.0 compatibility")
            }
        }
    }
}