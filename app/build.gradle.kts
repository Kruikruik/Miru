plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "com.miru.anime"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.miru.anime"
        minSdk = 26
        targetSdk = 35
        // Sur GitHub Actions, le numéro de build sert de numéro de version
        // (c'est ce que compare le bouton « Mettre à jour l'appli »).
        val build = System.getenv("GITHUB_RUN_NUMBER")?.toIntOrNull() ?: 1
        versionCode = build
        versionName = "1.0.$build"
    }

    // Clé de signature fixe : chaque nouvelle version s'installe par-dessus l'ancienne.
    // La clé n'est plus dans le dépôt : sur GitHub Actions elle vient des Secrets
    // (SIGNING_KEYSTORE / SIGNING_PASSWORD), décodée dans app/miru.keystore.
    val signingPassword = System.getenv("SIGNING_PASSWORD")
    val keystoreFile = file("miru.keystore")
    val hasSigningKey = signingPassword != null && keystoreFile.exists()
    signingConfigs {
        create("miru") {
            if (hasSigningKey) {
                storeFile = keystoreFile
                storePassword = signingPassword
                keyAlias = "miru"
                keyPassword = signingPassword
            } else {
                initWith(getByName("debug"))
            }
        }
    }

    buildTypes {
        debug {
            signingConfig = signingConfigs.getByName("miru")
        }
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfig = signingConfigs.getByName("miru")
        }
    }

    lint {
        checkReleaseBuilds = false
        abortOnError = false
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
    buildFeatures {
        compose = true
        buildConfig = true
    }
}

dependencies {
    val composeBom = platform("androidx.compose:compose-bom:2024.12.01")
    implementation(composeBom)
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-core")

    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.7")
    implementation("androidx.navigation:navigation-compose:2.8.5")


    implementation("io.coil-kt:coil-compose:2.7.0")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("org.jsoup:jsoup:1.18.3")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")
    implementation("androidx.profileinstaller:profileinstaller:1.4.1")
}
