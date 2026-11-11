







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
    compileOnly(libs.lint.api)
    compileOnly(libs.lint.checks)

    
    
    
    testImplementation(libs.lint.tests)
    testImplementation(libs.lint.api)
    testImplementation(libs.junit)
}

tasks.jar {
    manifest {
        
        
        
        attributes(
            "Lint-Registry-v2" to "com.difft.android.lint.Registry"
        )
    }
}
