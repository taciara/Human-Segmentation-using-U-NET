import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

val keystorePropertiesFile = rootProject.file("keystore.properties")
val keystoreProperties = Properties()
if (keystorePropertiesFile.exists()) {
    keystorePropertiesFile.inputStream().use { keystoreProperties.load(it) }
}

android {
    namespace = "online.seuprojeto.filtrofanta"
    compileSdk = 35
    defaultConfig {
        // Pacote próprio → app separado na Play / instala ao lado do Cabine Fanta antigo.
        applicationId = "online.seuprojeto.filtrofanta.cabine3"
        minSdk = 24
        targetSdk = 35
        versionCode = 104
        versionName = "5.7.20-native"
        val appDisplayName = "Cabine Fanta 3"
        manifestPlaceholders["appLabel"] = appDisplayName
        buildConfigField("String", "APP_DISPLAY_NAME", "\"$appDisplayName\"")
        buildConfigField("String", "SHARE_ORIGIN", "\"https://fanta-filtro.seuprojeto.online\"")
    }
    signingConfigs {
        if (keystorePropertiesFile.exists()) {
            create("release") {
                storeFile = rootProject.file(keystoreProperties["storeFile"] as String)
                storePassword = keystoreProperties["storePassword"] as String
                keyAlias = keystoreProperties["keyAlias"] as String
                keyPassword = keystoreProperties["keyPassword"] as String
            }
        }
    }
    androidResources {
        noCompress += listOf("tflite")
    }
    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = if (keystorePropertiesFile.exists()) {
                signingConfigs.getByName("release")
            } else {
                // Sideload / teste na tablet sem keystore de loja.
                signingConfigs.getByName("debug")
            }
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

/** APK sideload → android/cabine-fanta-3.apk */
tasks.register("exportCabineFanta3Apk") {
    dependsOn("assembleRelease")
    doLast {
        val release = layout.buildDirectory.file("outputs/apk/release/app-release.apk").get().asFile
        val dest = rootProject.layout.projectDirectory.file("cabine-fanta-3.apk").asFile
        release.copyTo(dest, overwrite = true)
    }
}

/** AAB para Google Play → android/cabine-fanta-3.aab */
tasks.register("exportCabineFanta3Aab") {
    dependsOn("bundleRelease")
    doLast {
        val bundle = layout.buildDirectory.file("outputs/bundle/release/app-release.aab").get().asFile
        val dest = rootProject.layout.projectDirectory.file("cabine-fanta-3.aab").asFile
        bundle.copyTo(dest, overwrite = true)
    }
}

dependencies {
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("com.google.mediapipe:tasks-vision:0.10.14")
    implementation("com.herohan:UVCAndroid:1.0.13")
    implementation("com.google.zxing:core:3.5.3")
}
