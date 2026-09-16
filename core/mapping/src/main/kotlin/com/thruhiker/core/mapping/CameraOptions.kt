package com.thruhiker.core.mapping

import com.thruhiker.core.model.LatLng
import org.maplibre.android.camera.CameraPosition
import org.maplibre.android.geometry.LatLng as MapLibreLatLng

/**
 * Engine-agnostic description of where the camera is looking.
 *
 * Kept separate from MapLibre's own `CameraPosition` so that camera maths,
 * keyframe interpolation and the flyover timeline can all be tested on the JVM
 * without a map instance.
 */
data class CameraOptions(
  val target: LatLng,
  val zoom: Double = DEFAULT_ZOOM,
  val tilt: Double = 0.0,
  val bearing: Double = 0.0,
) {
  internal fun toCameraPosition(): CameraPosition = CameraPosition.Builder()
    .target(MapLibreLatLng(target.latitude, target.longitude))
    .zoom(zoom)
    .tilt(tilt)
    .bearing(bearing)
    .build()

  companion object {
    const val DEFAULT_ZOOM = 12.0

    /**
     * Default pitch for the cinematic camera.
     *
     * Around 60 degrees is the sweet spot for reading relief: much flatter and
     * the terrain is invisible, much steeper and the horizon eats the screen.
     */
    const val CINEMATIC_TILT = 60.0
  }
}
