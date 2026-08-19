import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("com.chaquo.python")
}

val releaseSigningPropertiesFile = providers.gradleProperty("archipelago.releaseSigningProperties")
    .map(::file)
    .orElse(
        providers.provider {
            File(
                System.getProperty("user.home"),
                ".android/eu.odran.archipelago-release.properties",
            )
        },
    )
    .get()

val releaseSigningProperties = Properties().apply {
    if (releaseSigningPropertiesFile.isFile) {
        releaseSigningPropertiesFile.inputStream().use(::load)
    }
}

android {
    namespace = "eu.odran.archipelago"
    compileSdk = 35

    defaultConfig {
        applicationId = "eu.odran.archipelago"
        minSdk = 26
        targetSdk = 35
        versionCode = 75
        versionName = "0.24.1"

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

    signingConfigs {
        create("release") {
            storeFile = releaseSigningProperties.getProperty("storeFile")?.let(::file)
            storePassword = releaseSigningProperties.getProperty("storePassword")
            keyAlias = releaseSigningProperties.getProperty("keyAlias")
            keyPassword = releaseSigningProperties.getProperty("keyPassword")
            enableV1Signing = true
            enableV2Signing = true
            enableV3Signing = true
        }
    }

    buildTypes {
        getByName("release") {
            signingConfig = signingConfigs.getByName("release")
        }
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
            install("platformdirs==4.10.1")
            install("typing_extensions==4.15.0")
            install("requests==2.32.5")
        }
    }
}

kotlin {
    jvmToolchain(17)
}

dependencies {
    implementation("androidx.core:core:1.15.0")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    testImplementation("junit:junit:4.13.2")
}
