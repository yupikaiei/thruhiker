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

  // Exposed as api because TerrariumTileSource is a DemTileSource: feature modules construct
  // the Android implementation and hand it straight to core:dem's sampler, so the seam type
  // has to be on their compile classpath.
  api(project(":core:dem"))

  // Exposed as api so that feature modules can drive the map without taking a
  // second, potentially mismatched, MapLibre dependency of their own.
  //
  // When the locally built 3D-terrain SDK is present it stands in for the published
  // artifact: same classes, plus a native library that implements terrain. See README,
  // "3D terrain".
  //
  // It carries only an arm64-v8a native library, so a build for an x86_64 emulator opts
  // out of it:
  //
  //     ./gradlew assembleDebug -Pthruhiker.vendoredTerrain=false
  val vendoredTerrain =
    (providers.gradleProperty("thruhiker.vendoredTerrain").orNull?.toBoolean() ?: true) &&
      rootProject.file("third_party/maplibre-android").isDirectory

  if (vendoredTerrain) {
    api(project(":maplibre-terrain"))
  } else {
    // Deliberately the OpenGL flavor, not the plain `android-sdk` artifact. That one is a
    // Vulkan-only build — its RenderingEngine returns VULKAN unconditionally and refuses to
    // be switched to OpenGL — so it hard-crashes the render thread on any device without a
    // Vulkan driver, including every emulator. Same classes, same version, working backend.
    api(libs.maplibre.android.opengl)
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
