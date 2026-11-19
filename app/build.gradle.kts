plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "dev.lucudar.polka"
    compileSdk = 35

    defaultConfig {
        applicationId = "dev.lucudar.polka"
        minSdk = 26
        targetSdk = 35
        versionCode = 8
        versionName = "0.6.2"
    }

    // Ключ лежит в репозитории, чтобы обновления ставились поверх без удаления.
    // Для личного лаунчера этого достаточно; для публикации замени на свой.
    signingConfigs {
        create("polka") {
            storeFile = file("polka.p12")
            storePassword = "polka-launcher"
            keyAlias = "polka"
            keyPassword = "polka-launcher"
            storeType = "pkcs12"
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfig = signingConfigs.getByName("polka")
        }
        debug {
            signingConfig = signingConfigs.getByName("polka")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
        // BringIntoViewRequester в Compose 1.7 ещё помечен экспериментальным.
        freeCompilerArgs += listOf("-opt-in=androidx.compose.foundation.ExperimentalFoundationApi")
    }
    buildFeatures {
        compose = true
    }
    lint {
        checkReleaseBuilds = false
        abortOnError = false
    }
}

dependencies {
    val composeBom = platform("androidx.compose:compose-bom:2024.12.01")
    implementation(composeBom)
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.material:material-icons-core")
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.core:core-ktx:1.15.0")
    // Ставит профиль горячего кода (свой и Compose) после установки: ART компилирует его заранее,
    // а не гоняет интерпретатор на A35. Без Play это единственный путь к быстрому первому запуску.
    implementation("androidx.profileinstaller:profileinstaller:1.3.1")
}
