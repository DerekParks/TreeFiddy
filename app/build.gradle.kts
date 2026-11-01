plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.treefiddy.sms"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.treefiddy.sms"
        minSdk = 26
        targetSdk = 34
        versionCode = 1
        versionName = "1.0"
        ndk { abiFilters += "arm64-v8a" }   // Pixels are arm64-only; ~35 MB vs ~55 MB
    }

    // Release key lives outside the repo: set treefiddy.keystore / .storePassword /
    // .keyAlias / .keyPassword in ~/.gradle/gradle.properties. Absent -> unsigned release.
    val releaseKeystore = providers.gradleProperty("treefiddy.keystore").orNull
    signingConfigs {
        if (releaseKeystore != null) create("release") {
            storeFile = file(releaseKeystore)
            storePassword = providers.gradleProperty("treefiddy.storePassword").get()
            keyAlias = providers.gradleProperty("treefiddy.keyAlias").get()
            keyPassword = providers.gradleProperty("treefiddy.keyPassword").get()
        }
    }

    buildFeatures {
        buildConfig = true
    }

    // CRITICAL: don't let aapt compress the model, or the mmap in SpamClassifier fails.
    androidResources {
        noCompress += "tflite"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.findByName("release")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
        // LiteRT's api jar is built with a newer Kotlin; allow consuming its metadata.
        freeCompilerArgs = freeCompilerArgs + "-Xskip-metadata-version-check"
    }
}

dependencies {
    // LiteRT (successor to tensorflow-lite) ships 16 KB-page-aligned native libs,
    // required by modern Pixels (Android 15/16, 16 KB memory pages).
    // Exclusions: we only run a local Interpreter over an mmapped asset —
    // Play asset-delivery (Play-Store model downloads) and WorkManager
    // (background scheduling) are unused and merge unwanted permissions
    // (FOREGROUND_SERVICE*, WAKE_LOCK, ACCESS_NETWORK_STATE, BOOT_COMPLETED)
    // into the manifest, undermining the no-network-permission guarantee.
    implementation("com.google.ai.edge.litert:litert:2.1.5") {
        exclude(group = "com.google.android.play")
        exclude(group = "androidx.work")
    }
}
