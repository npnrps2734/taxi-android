plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    // Push-уведомления (Firebase Cloud Messaging) — применяется ПОСЛЕ основных
    // плагинов, читает app/google-services.json (не секретный файл, содержит
    // только публичный API-ключ приложения — можно спокойно коммитить).
    id("com.google.gms.google-services")
}

// ID проекта RuStore Push для «UMKA Водитель» (RuStore Консоль → приложение водителя →
// Push-уведомления → Проекты). Не секрет — так же, как ID пассажирского проекта в манифесте.
val driverRustoreProjectId: String = (project.findProperty("RUSTORE_DRIVER_PROJECT_ID") as String?)?.trim() ?: ""

// Google-push (Firebase) для «UMKA Водитель» включается, когда в app/google-services.json
// появится приложение ru.taxiapp.driver (Firebase Console → Добавить приложение → Android).
// До этого сборка не падает: обработка google-services для водителя просто пропускается,
// а уведомления водителю идут через RuStore Push.
val firebaseHasDriver: Boolean = file("google-services.json").let { it.exists() && it.readText().contains("\"ru.taxiapp.driver\"") }
tasks.configureEach {
    if (!firebaseHasDriver && name.startsWith("process") && name.contains("Driver") && name.endsWith("GoogleServices")) enabled = false
}

android {
    namespace = "ru.taxiapp.app"
    compileSdk = 34

    defaultConfig {
        minSdk = 24
        targetSdk = 34
    }

    buildFeatures {
        buildConfig = true
    }

    // Два приложения из одного кода:
    //   passenger — «UMKA Такси» для пассажиров (тот же пакет ru.taxiapp.app, что уже в RuStore —
    //               у пассажиров оно просто обновится);
    //   driver    — «UMKA Водитель» (новый пакет ru.taxiapp.driver): работа в фоне на линии,
    //               своя иконка с подписью «ВОДИТЕЛЬ».
    // Какое из них запущено, страница узнаёт через UmkaApp.appKind() (см. MainActivity).
    flavorDimensions += "app"
    productFlavors {
        create("passenger") {
            dimension = "app"
            applicationId = "ru.taxiapp.app"
            versionCode = 4
            versionName = "1.3"
            resValue("string", "app_name", "UMKA Такси")
            buildConfigField("String", "APP_KIND", "\"passenger\"")
            manifestPlaceholders["rustoreProjectId"] = "b0p3XL0lkDtR9gG3CpXBc3ohQavojb0W"
        }
        create("driver") {
            dimension = "app"
            applicationId = "ru.taxiapp.driver"
            versionCode = 2
            versionName = "1.0.1"
            resValue("string", "app_name", "UMKA Водитель")
            buildConfigField("String", "APP_KIND", "\"driver\"")
            // ID проекта RuStore Push приложения водителя — gradle.properties (RUSTORE_DRIVER_PROJECT_ID).
            // Пока пусто — RuStore Push в приложении водителя не подключается (см. sourceSets ниже).
            manifestPlaceholders["rustoreProjectId"] = driverRustoreProjectId
        }
    }
    sourceSets {
        getByName("driver") {
            manifest.srcFile(if (driverRustoreProjectId.isBlank()) "src/driver/AndroidManifest-nopush.xml" else "src/driver/AndroidManifest.xml")
        }
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

    // Push-уведомления через RuStore (для телефонов с приложением из RuStore, без Google)
    implementation("ru.rustore.sdk:pushclient:7.3.0")
}
