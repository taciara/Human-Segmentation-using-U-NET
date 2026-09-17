plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "online.seuprojeto.filtrofanta"
    compileSdk = 35
    defaultConfig {
        applicationId = "online.seuprojeto.filtrofanta"
        minSdk = 24
        targetSdk = 35
        versionCode = 37
        versionName = "5.3.18-native"
        buildConfigField("String", "SHARE_ORIGIN", "\"https://fanta-filtro.seuprojeto.online\"")
    }
    androidResources {
        noCompress += listOf("tflite")
    }
    buildTypes {
        release {
            isMinifyEnabled = false
        }
    }
    buildFeatures {
        buildConfig = true
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
}

dependencies {
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("com.google.mediapipe:tasks-vision:0.10.14")
    implementation("com.herohan:UVCAndroid:1.0.13")
    implementation("com.google.zxing:core:3.5.3")
}
