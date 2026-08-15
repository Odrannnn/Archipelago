plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "gg.archipelago.android"
    compileSdk = 35

    defaultConfig {
        applicationId = "gg.archipelago.android"
        minSdk = 26
        targetSdk = 35
        versionCode = 3
        versionName = "0.3.0"
    }

    // AGP 8.x and Kotlin 2.x both run on JDK 17. Keep javac and kotlinc
    // targeting the same bytecode level so Gradle can build a mixed project.
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

kotlin {
    jvmToolchain(17)
}

dependencies {
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
}
