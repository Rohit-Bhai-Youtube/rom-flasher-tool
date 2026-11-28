import java.io.FileInputStream
import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.compose.compiler)
}

// ---------------------------------------------------------------------------
//  Release signing.
//  Reads the git-ignored `key.properties` at the repo root, which points at
//  `keystore/romflasher-release.jks`. When the file is missing (fresh clone,
//  or CI without secrets) the release build falls back to the debug keystore
//  so `assembleRelease` still yields an installable APK instead of failing.
// ---------------------------------------------------------------------------
val keystorePropertiesFile = rootProject.file("key.properties")
val keystoreProperties = Properties().apply {
    if (keystorePropertiesFile.exists()) {
        FileInputStream(keystorePropertiesFile).use { load(it) }
    }
}

android {
    namespace = "com.romflasher.tool"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.romflasher.tool"
        minSdk = 26
        targetSdk = 34
        versionCode = 27
        versionName = "1.6.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        vectorDrawables {
            useSupportLibrary = true
        }
    }

    signingConfigs {
        create("release") {
            if (keystorePropertiesFile.exists()) {
                storeFile = rootProject.file(keystoreProperties.getProperty("storeFile"))
                storePassword = keystoreProperties.getProperty("storePassword")
                keyAlias = keystoreProperties.getProperty("keyAlias")
                keyPassword = keystoreProperties.getProperty("keyPassword")
                enableV2Signing = true
                enableV3Signing = true
            }
        }
    }

    buildTypes {
        debug {
            // Debug builds are NEVER obfuscated - never ship these to users.
            isMinifyEnabled = false
        }
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            isDebuggable = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            // Do not ship native debug symbols with the release build.
            ndk {
                debugSymbolLevel = "NONE"
            }
            // Signed with the private release keystore (keystore/romflasher-release.jks)
            // so published APKs can be updated in place. Falls back to the local debug
            // keystore only when key.properties is absent.
            signingConfig = if (keystorePropertiesFile.exists()) {
                signingConfigs.getByName("release")
            } else {
                signingConfigs.getByName("debug")
            }
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
    buildFeatures {
        compose = true
        buildConfig = true
    }
    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
        jniLibs {
            useLegacyPackaging = false
        }
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.activity.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.ui)
    implementation(libs.androidx.ui.graphics)
    implementation(libs.androidx.material3)
    implementation(libs.androidx.material.icons.extended)

    // Archive handling (Zip, 7z, Tar, Gz, Zstd)
    implementation(libs.apache.commons.compress)
    implementation(libs.tukaani.xz)
    implementation("com.github.luben:zstd-jni:1.5.5-11@aar")

    // QR code generation (UPI donate QR shown in the Donate dialog)
    implementation("com.google.zxing:core:3.5.3")

    debugImplementation(libs.androidx.ui.tooling)
}
