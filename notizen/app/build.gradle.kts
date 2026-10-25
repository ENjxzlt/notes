plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "online.minihomelab.notizen"
    compileSdk = 34

    defaultConfig {
        applicationId = "online.minihomelab.notizen"
        minSdk = 26
        targetSdk = 34
        // Im CI steigt die Nummer mit jedem Lauf, damit Updates über die alte Version installierbar bleiben
        versionCode = System.getenv("GITHUB_RUN_NUMBER")?.toIntOrNull() ?: 1
        versionName = "0.1"
    }

    // Feste Signatur (debug.keystore im Repo), sonst wäre jeder CI-Build anders signiert
    // und ließe sich nicht über die vorige Version installieren (Notizen gingen beim Deinstallieren verloren)
    signingConfigs {
        getByName("debug") {
            storeFile = rootProject.file("debug.keystore")
            storePassword = "android"
            keyAlias = "androiddebugkey"
            keyPassword = "android"
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
