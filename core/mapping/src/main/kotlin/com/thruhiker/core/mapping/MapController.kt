package com.thruhiker.core.mapping

import com.thruhiker.core.model.LatLng
import org.maplibre.android.maps.MapLibreMap

/**
 * Thin handle over a live map.
 *
 * Everything the rest of the app may do to the map goes through here, so that
 * swapping the rendering engine later, or adding an offline-only variant, does
 * not ripple out into the feature modules.
 */
class MapController internal constructor(private val map: MapLibreMap) {

  internal fun applyStyle(styleJson: String) {
    map.setStyle(styleJson)
  }

  /** Moves the camera immediately, with no animation. The flyover rig calls this per frame. */
  fun moveCamera(options: CameraOptions) {
    map.cameraPosition = options.toCameraPosition()
  }

  /**
   * The current camera, or null when the map has no target yet, which is the case
   * before its first style load. Returning null rather than a placeholder
   * coordinate keeps callers from treating (0, 0) in the Gulf of Guinea as a real
   * camera position.
   */
  fun camera(): CameraOptions? {
    val position = map.cameraPosition
    val target = position.target ?: return null
    return CameraOptions(
      target = LatLng(target.latitude, target.longitude),
      zoom = position.zoom,
      tilt = position.tilt,
      bearing = position.bearing,
    )
  }
}
