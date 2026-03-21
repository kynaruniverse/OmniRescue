plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.omni.rescue"
    compileSdk = 36   // Android 17

    defaultConfig {
        applicationId = "com.omni.rescue"
        minSdk = 36    // target Android 16/17
        targetSdk = 36
        versionCode = 1
        versionName = "1.0"
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
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

dependencies {
    implementation(libs.tensorflow.lite)
    // // implementation(libs.tensorflow.lite.support)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.appcompat)
    implementation(libs.material)
    // For lifecycle (optional)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    // For camera flash control (we'll use CameraManager directly, no extra libs)
}