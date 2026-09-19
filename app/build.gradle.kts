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

    signingConfigs {
        create("release") {
            // Cle auto-generee localement (voir README), juste pour pouvoir installer l'APK
            // en dehors du Play Store. Pas destinee a une vraie publication.
            storeFile = file("release-key.jks")
            storePassword = "interrupteur123"
            keyAlias = "interrupteur"
            keyPassword = "interrupteur123"
        }
    }

    buildTypes {
        release {
            // Supprime le code et les ressources non utilisees (surtout dans les librairies
            // AndroidX/Material) : c'est ce qui reduit vraiment la taille de l'APK.
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfig = signingConfigs.getByName("release")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    lint {
        // Desactive : plante avec ce JDK tres recent (analyse Kotlin/UAST interne au lint,
        // meme souci que le demon Kotlin plus haut). Sans rapport avec R8/minification.
        checkReleaseBuilds = false
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
