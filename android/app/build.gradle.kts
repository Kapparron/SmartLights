plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}
// La versión y su código los fija el flujo de publicación (-PappVersion, -PappVersionCode).
val appVersion = (project.findProperty("appVersion") as String?) ?: "1.0"
val appVersionCode = (project.findProperty("appVersionCode") as String?)?.toInt() ?: 1
// La clave de firma llega por variables de entorno; si no están, el APK de release queda sin firmar.
val keystorePath: String? = System.getenv("KEYSTORE_PATH")

android {
    namespace = "luces.app"
    compileSdk = 34
    defaultConfig {
        applicationId = "com.smartlights.app"
        minSdk = 26
        targetSdk = 34
        versionCode = appVersionCode
        versionName = appVersion
    }
    signingConfigs {
        if (keystorePath != null) {
            create("release") {
                storeFile = file(keystorePath)
                storeType = "pkcs12"
                storePassword = System.getenv("KEYSTORE_PASSWORD")
                keyAlias = System.getenv("KEY_ALIAS")
                keyPassword = System.getenv("KEYSTORE_PASSWORD")
            }
        }
    }
    buildTypes {
        release {
            signingConfig = signingConfigs.findByName("release")
        }
    }
    lint {
        abortOnError = false
        checkReleaseBuilds = false
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
}
dependencies {
    implementation(project(":core"))
}
