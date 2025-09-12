plugins {
    alias(libs.plugins.android.application)
}

android {
    namespace = "com.marcoscarvalho.evernear"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.marcoscarvalho.evernear"
        minSdk = 28
        targetSdk = 36
        versionCode = 1
        versionName = "1.0"

    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
}

dependencies {

    implementation(libs.play.services.wearable)
}