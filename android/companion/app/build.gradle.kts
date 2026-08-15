plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("com.chaquo.python")
}

android {
    namespace = "gg.archipelago.android"
    compileSdk = 35

    defaultConfig {
        applicationId = "gg.archipelago.android"
        minSdk = 26
        targetSdk = 35
        versionCode = 8
        versionName = "0.6.0"

        ndk {
            // The custom mGBA bridge currently targets 64-bit Android devices.
            abiFilters += listOf("arm64-v8a")
        }
    }

    // AGP 8.x and Kotlin 2.x both run on JDK 17. Keep javac and kotlinc
    // targeting the same bytecode level so Gradle can build a mixed project.
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

chaquopy {
    defaultConfig {
        version = "3.12"
        // Archipelago discovers APWorlds with os.scandir and the MARS patcher
        // reads adjacent JSON/BPS resources, so this package must be physical.
        extractPackages("worlds")
        providers.gradleProperty("chaquopy.buildPython").orNull?.let {
            buildPython(it)
        }
        pip {
            install("PyYAML==6.0.3")
            install("schema==0.7.8")
            install("pathspec==1.0.4")
            install("typing_extensions==4.15.0")
        }
    }
}

kotlin {
    jvmToolchain(17)
}

dependencies {
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
}
