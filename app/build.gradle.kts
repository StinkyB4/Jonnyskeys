plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.jonnyskeys.keytap"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.jonnyskeys.keytap"
        // minSdk 26 is required for GestureDescription.StrokeDescription.continueStroke,
        // which is what lets a held key hold the touch down indefinitely.
        minSdk = 26
        targetSdk = 35
        // CI passes GITHUB_RUN_NUMBER so every Play upload gets a higher versionCode.
        versionCode = (System.getenv("VERSION_CODE") ?: "1").toInt()
        versionName = "1.0"
    }

    // Release signing comes from environment variables so the keystore never
    // lives in the repo. CI decodes the ANDROID_KEYSTORE_BASE64 secret to a
    // file and exports these; locally you can export them before building.
    val keystorePath: String? = System.getenv("ANDROID_KEYSTORE_FILE")
    if (keystorePath != null) {
        signingConfigs {
            create("release") {
                storeFile = file(keystorePath)
                storePassword = System.getenv("ANDROID_KEYSTORE_PASSWORD")
                keyAlias = System.getenv("ANDROID_KEY_ALIAS")
                keyPassword = System.getenv("ANDROID_KEY_PASSWORD")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            if (keystorePath != null) {
                signingConfig = signingConfigs.getByName("release")
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
}
