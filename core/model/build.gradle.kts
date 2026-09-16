plugins {
  alias(libs.plugins.kotlin.jvm)
}

kotlin {
  jvmToolchain(17)
}

dependencies {
  // Pure Kotlin domain types: deliberately dependency-free so that every other
  // module can depend on it without dragging in a platform.
  testImplementation(libs.junit)
}
