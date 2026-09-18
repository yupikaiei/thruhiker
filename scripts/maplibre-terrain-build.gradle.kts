// MapLibre Android SDK built locally with 3D terrain support.
//
// This file is the build script for the throwaway module at
// `third_party/maplibre-android/`, which `scripts/build-maplibre-terrain.sh`
// assembles and which is deliberately not checked in. The script copies this file
// into the module, because everything inside that module is generated from
// upstream and would otherwise have to be written twice.
//
// See README.md, "3D terrain".
//
// Upstream builds the renderer strategies as product flavours; only the OpenGL
// flavour is vendored here because OpenGL is the backend the terrain branch tests
// most on Android.
plugins {
  alias(libs.plugins.android.library)
}

android {
  namespace = "org.maplibre.android"
  compileSdk = 36

  defaultConfig {
    minSdk = 26

    consumerProguardFiles("proguard-rules.pro")

    // Upstream generates these; a couple of log statements read them.
    buildConfigField("String", "GIT_REVISION_SHORT", "\"terrain-3d\"")
    buildConfigField("String", "GIT_REVISION", "\"maplibre-native feature/terrain-3d\"")
    buildConfigField("String", "MAPLIBRE_VERSION_STRING", "\"MapLibre Android/13.6.1+terrain\"")
  }

  sourceSets {
    getByName("main") {
      java.srcDirs("src/main/java", "src/opengl/java", "src/sharedRenderer/opengl/java")
      res.srcDirs("src/main/res", "src/main/res-public")
    }
  }

  buildFeatures {
    buildConfig = true
  }

  compileOptions {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
  }

  lint {
    // Vendored upstream code: report it, but never fail the app's build on it.
    abortOnError = false
    checkAllWarnings = false
  }
}

kotlin {
  jvmToolchain(17)
}

dependencies {
  api("org.maplibre.gl:android-sdk-geojson:6.0.1")
  api("org.maplibre.gl:maplibre-android-gestures:0.0.4")

  implementation("org.maplibre.gl:android-sdk-turf:6.0.1")
  implementation("androidx.annotation:annotation:1.8.2")
  implementation("androidx.fragment:fragment:1.8.9")
  implementation("androidx.interpolator:interpolator:1.0.0")
  implementation("com.squareup.okhttp3:okhttp:4.12.0")
  implementation("com.jakewharton.timber:timber:5.0.1")
  implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.10.2")
}
