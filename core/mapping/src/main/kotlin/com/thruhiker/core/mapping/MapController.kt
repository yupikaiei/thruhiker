package com.thruhiker.core.mapping

import com.thruhiker.core.model.CameraOptions
import com.thruhiker.core.model.LatLng
import org.maplibre.android.maps.MapLibreMap
import org.maplibre.android.maps.Style
import org.maplibre.android.style.sources.GeoJsonSource

/**
 * Thin handle over a live map.
 *
 * Everything the rest of the app may do to the map goes through here, so that
 * swapping the rendering engine later, or adding an offline-only variant, does
 * not ripple out into the feature modules.
 */
class MapController internal constructor(private val map: MapLibreMap) {

  internal fun applyStyle(styleJson: String) {
    map.setStyle(styleBuilderFor(styleJson))
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

  /**
   * Replaces the drawn route without rebuilding the style.
   *
   * The flyover calls this many times a second, and re-applying the whole style
   * would mean re-parsing every basemap layer and re-fetching nothing but
   * re-uploading everything. Swapping a GeoJSON source is the one update that is
   * cheap enough to drive an animation.
   *
   * No-op until the style has loaded, or if the route source is absent, which is
   * the case before any route has been imported.
   */
  fun updateTrack(geoJson: String) {
    map.getStyle { style ->
      style.getSourceAs<GeoJsonSource>(MapStyleFactory.TRACK_SOURCE_ID)
        ?.setGeoJson(geoJson)
    }
  }
}

/**
 * Builds a style from an in-memory JSON document.
 *
 * The obvious call — `map.setStyle(styleJson)` — is a trap. In this SDK that
 * overload is the *URL* one: it forwards to [Style.Builder.fromUri], so the
 * renderer tries to fetch the style document as if it were an address. The fetch
 * fails, no style is ever installed, and the map stays empty with no tiles and no
 * error surfaced to the app.
 *
 * A style document therefore has to go through [Style.Builder.fromJson], which is
 * what [MapController.applyStyle] relies on. This is kept as a pure function so a
 * JVM unit test can pin the shape of the builder instead of leaving the mistake to
 * be rediscovered on a device.
 */
internal fun styleBuilderFor(styleJson: String): Style.Builder =
  Style.Builder().fromJson(styleJson)
