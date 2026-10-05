pluginManagement {
  repositories {
    google {
      content {
        includeGroupByRegex("androidx.*")
        includeGroupByRegex("com\\.android.*")
        includeGroupByRegex("com\\.google.*")
      }
    }
    mavenCentral()
    gradlePluginPortal()
  }
}

dependencyResolutionManagement {
  repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
  repositories {
    google {
      content {
        includeGroupByRegex("androidx.*")
        includeGroupByRegex("com\\.android.*")
        includeGroupByRegex("com\\.google.*")
      }
    }
    mavenCentral()
  }
}

plugins {
  id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
}

rootProject.name = "ThruHiker"

include(":app")
include(":core:model")
include(":core:geo")
include(":core:gpx")
include(":core:dem")
include(":core:flyover")
include(":core:designsystem")
include(":core:mapping")

// The vendored 3D-terrain SDK is arm64-v8a only, so a build aimed at an x86_64
// emulator has to opt out of it:
//
//     ./gradlew assembleDebug -Pthruhiker.vendoredTerrain=false
val vendoredTerrainProperty = "thruhiker.vendoredTerrain"
val vendoredTerrainDirectory = "third_party/maplibre-android"

// The 3D-terrain MapLibre SDK is built locally and is not in version control, so it
// is only part of the build once `scripts/build-maplibre-terrain.sh` has run. Without
// it the app builds against the published SDK from the version catalog.
val vendoredTerrain =
  (providers.gradleProperty(vendoredTerrainProperty).orNull?.toBoolean() ?: true) &&
    file(vendoredTerrainDirectory).isDirectory

if (vendoredTerrain) {
  include(":maplibre-terrain")
  project(":maplibre-terrain").projectDir = file(vendoredTerrainDirectory)
}
