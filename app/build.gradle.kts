plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.example.mobilescanner"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.example.mobilescanner"
        minSdk = 26
        targetSdk = 34
        // CI passes GITHUB_RUN_NUMBER so every build has a higher versionCode, which Android requires for updates.
        versionCode = System.getenv("GITHUB_RUN_NUMBER")?.toIntOrNull() ?: 1
        versionName = "1.1.0"
    }

    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
        }
    }

    // Release signing comes from the environment so the key never lives in the repository.
    // See docs/RELEASE.md. Without these variables, release builds are left unsigned.
    val keystorePath = System.getenv("ABYSS_KEYSTORE_PATH")
    signingConfigs {
        if (keystorePath != null) {
            create("release") {
                storeFile = file(keystorePath)
                storePassword = System.getenv("ABYSS_KEYSTORE_PASSWORD")
                keyAlias = System.getenv("ABYSS_KEY_ALIAS")
                keyPassword = System.getenv("ABYSS_KEY_PASSWORD")
            }
        }
    }
    buildTypes {
        release {
            signingConfig = signingConfigs.findByName("release")
            // Shrinking is left off on purpose: the JNI bridge and Compose have not been verified under R8 on a device.
            isMinifyEnabled = false
        }
    }

    buildFeatures {
        compose = true
    }
    composeOptions {
        kotlinCompilerExtensionVersion = "1.5.8"
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.12.0")
    implementation("androidx.activity:activity-compose:1.8.2")
    implementation(platform("androidx.compose:compose-bom:2024.02.00"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.material3:material3")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.7.3")
    // Keystore-backed encrypted storage for the 15-minute session token.
    implementation("androidx.security:security-crypto:1.1.0-alpha06")

    testImplementation("junit:junit:4.13.2")
}
