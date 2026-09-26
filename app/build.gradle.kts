plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    // Push-уведомления (Firebase Cloud Messaging) — применяется ПОСЛЕ основных
    // плагинов, читает app/google-services.json (не секретный файл, содержит
    // только публичный API-ключ приложения — можно спокойно коммитить).
    id("com.google.gms.google-services")
}

android {
    namespace = "ru.taxiapp.app"
    compileSdk = 34

    defaultConfig {
        applicationId = "ru.taxiapp.app"
        minSdk = 24
        targetSdk = 34
        versionCode = 1
        versionName = "1.0"
    }

    // Подпись релизной сборки — ключ и пароли приходят из переменных
    // окружения, которые задаёт GitHub Actions из репозиторных секретов (см.
    // .github/workflows/build-android.yml и RELEASE_SIGNING.md). Если этих
    // переменных нет (например, локальная сборка без секретов) —
    // signingConfig релизу просто не назначается, и сборка не ломается, как
    // и раньше, просто получится неподписанный APK.
    val releaseStoreFile = System.getenv("RELEASE_STORE_FILE")
    signingConfigs {
        if (releaseStoreFile != null) {
            create("release") {
                storeFile = file(releaseStoreFile)
                storePassword = System.getenv("RELEASE_STORE_PASSWORD")
                keyAlias = System.getenv("RELEASE_KEY_ALIAS")
                keyPassword = System.getenv("RELEASE_KEY_PASSWORD")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            if (releaseStoreFile != null) {
                signingConfig = signingConfigs.getByName("release")
            }
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
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("com.google.android.material:material:1.12.0")
    implementation("androidx.activity:activity-ktx:1.9.1")
    implementation("androidx.swiperefreshlayout:swiperefreshlayout:1.1.0")

    // Push-уведомления (Firebase Cloud Messaging) — BoM синхронизирует версии
    // всех firebase-библиотек между собой, конкретную версию messaging не
    // указываем отдельно.
    implementation(platform("com.google.firebase:firebase-bom:34.19.0"))
    implementation("com.google.firebase:firebase-messaging")
}
