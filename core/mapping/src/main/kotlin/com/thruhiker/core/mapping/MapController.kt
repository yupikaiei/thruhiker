package com.thruhiker.core.mapping

import com.thruhiker.core.model.CameraOptions
import com.thruhiker.core.model.LatLng
import org.maplibre.android.maps.MapLibreMap
import org.maplibre.android.maps.Style
import org.maplibre.android.style.sources.GeoJsonSource
import kotlin.math.PI

/**
 * Thin handle over a live map.
 *
 * Everything the rest of the app may do to the map goes through here, so that
 * swapping the rendering engine later, or adding an offline-only variant, does
 * not ripple out into the feature modules.
 */
class MapController internal constructor(private val map: MapLibreMap) {

  init {
    // MapLibre drops the tile level of detail away from the centre of the screen once
    // the camera is pitched past `tileLodPitchThreshold`. Its default is exactly 60
    // degrees, which is the tilt this app's cinematic camera sits at on every screen, so
    // the heuristic is permanently engaged and most of the view is drawn from coarser
    // tiles: a soft basemap and hillshading too blunt to read as relief. Terrain detail
    // is the whole point here, so the reduction is switched off.
    map.tileLodPitchThreshold = TILE_LOD_PITCH_THRESHOLD_RADIANS
    applyTerrainLoadBudget(map)
  }

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

/**
 * Spreads 3D-terrain loading across frames instead of doing all of it at once.
 *
 * Full detail on demand gives the sharpest possible first frame and is the SDK's default,
 * but it builds every newly revealed tile and drape in the frame it arrives, which stalls
 * it. A flyover is the worst case there is: the camera never stops moving, so there is a
 * fresh burst of terrain every second, and a stalled frame reads as a stutter.
 *
 * The budget mode renders the same final image, just later: detail that would have arrived
 * with a hitch instead arrives two frames on, which is invisible when the camera is crossing
 * ground at a kilometre a second.
 *
 * Only the terrain build of the SDK has this setting, and the app deliberately still builds
 * against the published one, which has no 3D terrain at all. The lookup is reflective for
 * that reason, and a missing method is left as a no-op rather than a failure: the setting is
 * a performance hint, not something correctness depends on.
 */
private fun applyTerrainLoadBudget(map: MapLibreMap) {
  val modeClass = runCatching { Class.forName(TERRAIN_LOAD_MODE_CLASS) }.getOrNull() ?: return
  val balanced = modeClass.enumConstants
    ?.firstOrNull { constant -> (constant as Enum<*>).name == TERRAIN_LOAD_MODE_BALANCED }
    ?: return
  runCatching {
    MapLibreMap::class.java
      .getMethod(TERRAIN_LOAD_MODE_SETTER, modeClass)
      .invoke(map, balanced)
  }
}

internal const val TERRAIN_LOAD_MODE_CLASS = "org.maplibre.android.maps.TerrainLoadMode"
internal const val TERRAIN_LOAD_MODE_SETTER = "setTerrainLoadMode"
internal const val TERRAIN_LOAD_MODE_BALANCED = "BALANCED"

/**
 * Tile level of detail is never reduced.
 *
 * MapLibre lowers the tile LOD away from the camera viewpoint above this pitch, which
 * saves tile requests on shallow, wide views. The SDK default is 60 degrees — and
 * [CameraOptions.CINEMATIC_TILT] is 60 degrees too, as is the flyover's travel tilt — so
 * leaving the default in place means the pitched camera this app renders *with* is always
 * over the line. `pi` is the value the MapLibre API documents as "LOD calculation is never
 * performed", which is what a map whose entire purpose is legible terrain needs.
 */
internal val TILE_LOD_PITCH_THRESHOLD_RADIANS: Double = PI
