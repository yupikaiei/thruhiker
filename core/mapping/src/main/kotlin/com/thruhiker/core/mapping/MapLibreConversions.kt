package com.thruhiker.core.mapping

import com.thruhiker.core.model.CameraOptions
import org.maplibre.android.camera.CameraPosition
import org.maplibre.android.geometry.LatLng as MapLibreLatLng

/**
 * The single place where an engine-independent [CameraOptions] becomes a MapLibre
 * camera.
 *
 * Keeping this out of the data class is the reason the flyover rig can be a plain
 * JVM module: nothing that computes a camera position has to know MapLibre exists.
 */
internal fun CameraOptions.toCameraPosition(): CameraPosition = CameraPosition.Builder()
  .target(MapLibreLatLng(target.latitude, target.longitude))
  .zoom(zoom)
  .tilt(tilt)
  .bearing(bearing)
  .build()
