plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.jonnyskeys.keytap"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.jonnyskeys.keytap"
        // minSdk 26 is required for GestureDescription.StrokeDescription.continueStroke,
        // which is what lets a held key hold the touch down indefinitely.
        minSdk = 26
        targetSdk = 34
        versionCode = 1
        versionName = "1.0"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
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
