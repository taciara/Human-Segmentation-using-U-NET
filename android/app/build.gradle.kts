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
        versionCode = 8
        versionName = "4.0"
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
    kotlinOptions { jvmTarget = "17" }
}

dependencies {
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.webkit:webkit:1.12.1")
    implementation("com.herohan:UVCAndroid:1.0.13")
}
