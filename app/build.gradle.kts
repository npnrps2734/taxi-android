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
