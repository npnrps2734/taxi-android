pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
        // RuStore Push SDK
        maven { url = uri("https://nexus-external.rustore.ru/repository/maven-rustore-exposed/") }
    }
}

rootProject.name = "TaxiApp"
include(":app")
