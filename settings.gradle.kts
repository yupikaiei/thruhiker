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
include(":core:flyover")
include(":core:designsystem")
include(":core:mapping")

// The 3D-terrain MapLibre SDK is built locally and is not in version control, so it
// is only part of the build once `scripts/build-maplibre-terrain.sh` has run. Without
// it the app builds against the published SDK from the version catalog.
val terrainMapLibreDir = file("third_party/maplibre-android")
if (terrainMapLibreDir.isDirectory) {
  include(":maplibre-terrain")
  project(":maplibre-terrain").projectDir = terrainMapLibreDir
}
