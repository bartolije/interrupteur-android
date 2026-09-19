plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.example.interrupteur"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.example.interrupteur"
        minSdk = 26          // Android 8.0 : suffisant, pas besoin de descendre plus bas pour ce projet pedagogique
        targetSdk = 34       // Android 14, la version du Samsung Galaxy A13 de test
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

dependencies {
    implementation("androidx.appcompat:appcompat:1.7.0")
    // Material Components : uniquement pour avoir de jolis boutons (coins arrondis, couleurs, effet "ripple") tout fait
    implementation("com.google.android.material:material:1.12.0")
}
