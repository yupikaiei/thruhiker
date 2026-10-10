plugins {
  alias(libs.plugins.android.application)
  alias(libs.plugins.compose.compiler)
  alias(libs.plugins.kotlin.serialization)
}

android {
  namespace = "com.thruhiker"
  compileSdk = 36

  defaultConfig {
    applicationId = "com.thruhiker"
    minSdk = 26
    targetSdk = 36
    versionCode = 1
    versionName = "0.1.0"
  }

  /**
   * A real signing key, when one is supplied.
   *
   * Read through `providers.environmentVariable` rather than `System.getenv` because this
   * project builds with the configuration cache on: a provider is a tracked input, so setting
   * or clearing these re-runs configuration instead of silently reusing a cached one.
   *
   * Absent in a fresh clone, which is why the release variant below falls back to the debug
   * key rather than failing to build at all.
   */
  fun signingValue(name: String): String? =
    providers.environmentVariable(name).orNull?.takeIf(String::isNotBlank)

  val keystorePath = signingValue("THRUHIKER_KEYSTORE")

  signingConfigs {
    if (keystorePath != null) {
      create("release") {
        storeFile = file(keystorePath)
        storePassword = signingValue("THRUHIKER_KEYSTORE_PASSWORD")
        keyAlias = signingValue("THRUHIKER_KEY_ALIAS")
        keyPassword = signingValue("THRUHIKER_KEY_PASSWORD")
      }
    }
  }

  buildTypes {
    release {
      isMinifyEnabled = false
      proguardFiles(
        getDefaultProguardFile("proguard-android-optimize.txt"),
        "proguard-rules.pro",
      )

      // A release APK is unsigned unless a signing config is given, and an unsigned APK
      // cannot be installed on a phone at all — `adb install` refuses it. So a release build
      // falls back to the debug keystore, which lets a build be sideloaded for testing
      // without inventing a key and a password to look after.
      //
      // That fallback is fine locally and a trap in CI: a runner is a fresh machine, so the
      // debug keystore is regenerated on every run and two builds come out signed with
      // different keys. Configure a keystore instead — see README, "Signing".
      signingConfig = signingConfigs.findByName("release")
        ?: signingConfigs.getByName("debug")
    }
  }

  compileOptions {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
  }

  buildFeatures {
    compose = true
    aidl = false
    buildConfig = false
    shaders = false
  }

  /**
   * MapLibre ships a ~13 MB native library per ABI, so a universal APK is about
   * 60 MB for no benefit: any given phone uses exactly one of them. Splitting per
   * ABI produces install-sized artifacts instead.
   *
   * The locally built 3D-terrain SDK only carries the arm64-v8a native library, so
   * with it in place the other splits would install an APK with no renderer at all.
   * Opt out of it to build for an x86_64 emulator:
   *
   *     ./gradlew assembleDebug -Pthruhiker.vendoredTerrain=false
   */
  val terrainSdk =
    (providers.gradleProperty("thruhiker.vendoredTerrain").orNull?.toBoolean() ?: true) &&
      rootProject.file("third_party/maplibre-android").isDirectory
  splits {
    abi {
      isEnable = true
      reset()
      if (terrainSdk) {
        include("arm64-v8a")
      } else {
        include("arm64-v8a", "armeabi-v7a", "x86_64")
      }
      isUniversalApk = false
    }
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
  implementation(project(":core:model"))
  implementation(project(":core:geo"))
  implementation(project(":core:gpx"))
  implementation(project(":core:flyover"))
  implementation(project(":core:designsystem"))
  implementation(project(":core:mapping"))

  val composeBom = platform(libs.androidx.compose.bom)
  implementation(composeBom)
  androidTestImplementation(composeBom)

  implementation(libs.androidx.core.ktx)
  implementation(libs.androidx.lifecycle.runtime.ktx)
  implementation(libs.androidx.activity.compose)

  implementation(libs.androidx.lifecycle.runtime.compose)
  implementation(libs.androidx.lifecycle.viewmodel.compose)

  implementation(libs.androidx.compose.ui)
  implementation(libs.androidx.compose.ui.graphics)
  implementation(libs.androidx.compose.ui.tooling.preview)
  implementation(libs.androidx.compose.material3)
  implementation(libs.androidx.compose.material.icons.core)

  implementation(libs.androidx.navigation3.runtime)
  implementation(libs.androidx.navigation3.ui)
  implementation(libs.androidx.lifecycle.viewmodel.navigation3)

  debugImplementation(libs.androidx.compose.ui.tooling)
  debugImplementation(libs.androidx.compose.ui.test.manifest)

  testImplementation(libs.junit)
  testImplementation(libs.kotlinx.coroutines.test)

  androidTestImplementation(libs.androidx.compose.ui.test.junit4)
  androidTestImplementation(libs.androidx.test.core)
  androidTestImplementation(libs.androidx.test.ext.junit)
  androidTestImplementation(libs.androidx.test.runner)
  androidTestImplementation(libs.androidx.test.espresso.core)
}
