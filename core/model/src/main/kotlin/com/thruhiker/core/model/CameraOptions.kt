package com.thruhiker.core.model

/**
 * Where a camera is looking, in engine-independent terms.
 *
 * This is deliberately free of any rendering SDK: the flyover timeline, the
 * keyframe interpolation and the framing heuristics are all arithmetic, and
 * arithmetic that runs on the JVM runs its tests in milliseconds instead of
 * needing an emulator. The MapLibre conversion lives in `core:mapping`.
 *
 * [tilt] is degrees from straight down: 0 is a map, 90 is the horizon.
 * [bearing] is degrees clockwise from true north.
 */
data class CameraOptions(
  val target: LatLng,
  val zoom: Double = DEFAULT_ZOOM,
  val tilt: Double = 0.0,
  val bearing: Double = 0.0,
) {
  companion object {
    const val DEFAULT_ZOOM = 12.0

    /**
     * Default pitch for the cinematic camera.
     *
     * Around 60 degrees is the sweet spot for reading relief: much flatter and the
     * terrain is invisible, much steeper and the horizon eats the screen.
     */
    const val CINEMATIC_TILT = 60.0
  }
}
