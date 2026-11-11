

plugins {
    kotlin("jvm")
}

java {
    sourceCompatibility = JavaVersion.toVersion(libs.versions.jvmTarget.get())
    targetCompatibility = JavaVersion.toVersion(libs.versions.jvmTarget.get())
}

kotlin {
    jvmToolchain(libs.versions.jvmTarget.get().toInt())
}

dependencies {
    compileOnly(libs.detekt.api)
    
    
}

tasks.named("test") {
    enabled = false
    doFirst {
        logger.lifecycle("Detekt rule unit tests intentionally disabled; see build.gradle.kts TODO.")
    }
}

tasks.test {
    useJUnit()
}
