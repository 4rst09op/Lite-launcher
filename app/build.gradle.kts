plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}
android {
    namespace = "com.ryan.launcher"
    compileSdk = 34
    defaultConfig {
        applicationId = "com.ryan.launcher"
        minSdk = 23
        targetSdk = 34
        versionCode = (project.findProperty("vc") as String?)?.toIntOrNull() ?: 2
        versionName = "5.1"
    }
    // Fixed signing key so future builds install OVER the old one
    signingConfigs {
        create("fixed") {
            storeFile = file("lite.keystore")
            storePassword = "litelauncher"
            keyAlias = "lite"
            keyPassword = "litelauncher"
        }
    }
    buildTypes {
        getByName("debug") { signingConfig = signingConfigs.getByName("fixed") }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_1_8
        targetCompatibility = JavaVersion.VERSION_1_8
    }
    kotlinOptions { jvmTarget = "1.8" }
}
