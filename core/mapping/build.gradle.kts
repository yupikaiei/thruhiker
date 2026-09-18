plugins {
  alias(libs.plugins.android.library)
  alias(libs.plugins.compose.compiler)
}

android {
  namespace = "com.thruhiker.core.mapping"
  compileSdk = 36

  defaultConfig {
    minSdk = 26
  }

  compileOptions {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
  }

  buildFeatures {
    compose = true
  }

  packaging {
    resources {
      excludes += "/META-INF/{AL2.0,LGPL2.1}"
    }
  }
}

kotlin {
  jvmToolchain(17)
}

dependencies {
  api(project(":core:model"))
  implementation(project(":core:geo"))

  // Exposed as api so that feature modules can drive the map without taking a
  // second, potentially mismatched, MapLibre dependency of their own.
  //
  // When the locally built 3D-terrain SDK is present it stands in for the published
  // artifact: same classes, plus a native library that implements terrain. See README,
  // "3D terrain".
  if (rootProject.file("third_party/maplibre-android").isDirectory) {
    api(project(":maplibre-terrain"))
  } else {
    api(libs.maplibre.android)
  }

  val composeBom = platform(libs.androidx.compose.bom)
  implementation(composeBom)
  implementation(libs.androidx.compose.ui)
  implementation(libs.androidx.lifecycle.runtime.compose)

  testImplementation(libs.junit)
  // Android ships a stubbed org.json that throws on every call, so the real
  // implementation is needed to exercise the style factory on the JVM.
  testImplementation(libs.json)
}
