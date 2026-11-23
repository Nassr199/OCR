import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "com.abdulrahman.hardsubocr"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.abdulrahman.hardsubocr"
        minSdk = 26
        targetSdk = 35
        versionCode = 11
        versionName = "2.7.1"
    }

    // One fixed key for debug AND release, so every new APK installs over the old one
    // (GitHub Actions would otherwise create a new random debug key on every build).
    // You can override it with repository secrets HSOCR_STORE_PASSWORD / HSOCR_KEY_ALIAS / HSOCR_KEY_PASSWORD.
    // GitHub passes a missing secret as an EMPTY string, so blank values fall back to the defaults.
    fun env(name: String, default: String): String = System.getenv(name)?.takeIf { it.isNotBlank() } ?: default

    signingConfigs {
        create("shared") {
            storeFile = rootProject.file("keystore/hardsubocr.jks")
            storePassword = env("HSOCR_STORE_PASSWORD", "hardsubocr")
            keyAlias = env("HSOCR_KEY_ALIAS", "hardsubocr")
            keyPassword = env("HSOCR_KEY_PASSWORD", "hardsubocr")
        }
    }

    buildTypes {
        debug {
            signingConfig = signingConfigs.getByName("shared")
        }
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfig = signingConfigs.getByName("shared")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }

    lint {
        // Lint must never block the APK for a beginner-friendly CI build.
        checkReleaseBuilds = false
        abortOnError = false
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
}

dependencies {
    val composeBom = platform("androidx.compose:compose-bom:2024.12.01")
    implementation(composeBom)

    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.7")
    implementation("androidx.lifecycle:lifecycle-viewmodel-ktx:2.8.7")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.7")

    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-core")

    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.7.3")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
}
