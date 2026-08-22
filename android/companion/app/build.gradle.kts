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

val desktopWorldsRoot = rootProject.file("../../worlds")
val n64SystemDeclaration = Regex("""system\s*=\s*[\"']N64[\"']""")
val bundledN64Worlds = desktopWorldsRoot.listFiles()
    .orEmpty()
    .filter { worldDirectory ->
        worldDirectory.isDirectory && worldDirectory.walkTopDown()
            .filter { it.isFile && it.extension.equals("py", ignoreCase = true) }
            .any { clientSource ->
                clientSource.readText().let { source ->
                    "BizHawkClient" in source && n64SystemDeclaration.containsMatchIn(source)
                }
            }
    }
    .sortedBy(File::getName)

val generatedN64Python = layout.buildDirectory.dir("generated/bundledN64Python")
val syncBundledN64Worlds = tasks.register<Sync>("syncBundledN64Worlds") {
    into(generatedN64Python)
    bundledN64Worlds.forEach { worldDirectory ->
        from(worldDirectory) {
            into("worlds/${worldDirectory.name}")
            exclude("test/**", "src/**", "**/__pycache__/**")
        }
    }
}

android {
    namespace = "eu.odran.archipelago"
    compileSdk = 35

    defaultConfig {
        applicationId = "eu.odran.archipelago"
        minSdk = 26
        targetSdk = 35
        versionCode = 88
        versionName = "0.36.0"

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
    sourceSets {
        getByName("main") {
            srcDir(syncBundledN64Worlds)
        }
    }
    defaultConfig {
        version = "3.12"
        // Archipelago discovers APWorlds with os.scandir and the MARS patcher
        // reads adjacent JSON/BPS resources, so this package must be physical.
        extractPackages("worlds")
        // The official Wind Waker patcher reads its ASM/data resources by path.
        extractPackages("twwrando")
        providers.gradleProperty("chaquopy.buildPython").orNull?.let {
            buildPython(it)
        }
        pip {
            install("PyYAML==6.0.3")
            install("schema==0.7.8")
            install("pathspec==1.0.4")
            install("platformdirs==4.10.1")
            install("packaging==25.0")
            install("typing_extensions==4.15.0")
            install("requests==2.32.5")
            install("ruamel.yaml==0.18.10")
            install("pillow==11.0.0")
            install("numpy==1.26.2")
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
    testImplementation("org.json:json:20240303")
}
