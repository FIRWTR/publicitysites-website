pluginManagement {
    repositories {
        google {
            content {
                includeGroupByRegex("com\\.android.*")
                includeGroupByRegex("com\\.google.*")
                includeGroupByRegex("androidx.*")
            }
        }
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
    }
}

rootProject.name = "unified-mesh"

// Application
include(":app")

// Feature layer (screens). Kept as a single Gradle module with one package per
// screen; see docs/ARCHITECTURE.md for why.
include(":feature")

// Core layer
include(":core:model")      // pure Kotlin/JVM
include(":core:bridge")     // pure Kotlin/JVM
include(":core:database")   // Android (Room)
include(":core:bluetooth")  // Android (BLE transport)
include(":core:radio")      // Android (radio session orchestration)

// Protocol layer
include(":protocol:api")         // pure Kotlin/JVM
include(":protocol:meshtastic")  // Android (protobuf + BLE framing)
include(":protocol:meshcore")    // Android (companion protocol framing)
