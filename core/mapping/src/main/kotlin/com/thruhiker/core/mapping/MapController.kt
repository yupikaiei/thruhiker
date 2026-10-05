package com.thruhiker.core.mapping

import com.thruhiker.core.model.CameraOptions
import com.thruhiker.core.model.LatLng
import org.maplibre.android.camera.CameraUpdateFactory
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

  /**
   * Pixels of the map view covered by something else, in left, top, right, bottom order.
   *
   * MapLibre centres the camera's target in the *map view*, but the flyover's info panel
   * covers the bottom of that view, so the middle of what the user can actually see sits
   * well above the middle of the view. Feeding the covered strip in as camera padding
   * moves the target to the middle of the uncovered part, which is the only thing that
   * makes the route look centred while the panel is up.
   */
  private var viewportPadding: DoubleArray? = null

  /** The route the app last asked to have drawn. See [drawRequestedRoute]. */
  private var requestedRoute: String? = null

  /** The gradient-coloured route the app last asked to have drawn. */
  private var requestedGradientRoute: String? = null

  /** The markers the app last asked to have drawn. */
  private var requestedWaypoints: String? = null

  /** The live tap handler, kept so it can be removed before another replaces it. */
  private var mapClickListener: MapLibreMap.OnMapClickListener? = null


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

  /**
   * Declares how much of the map view is covered by other UI.
   *
   * Called whenever the info panel changes height, which includes collapsing it for
   * playback: the framing follows the panel without the flyover having to know anything
   * about it.
   */
  fun setViewportPadding(left: Int = 0, top: Int = 0, right: Int = 0, bottom: Int = 0) {
    viewportPadding = cameraPadding(left, top, right, bottom)
  }

  /**
   * Moves the camera immediately, with no animation. The flyover rig calls this per frame.
   *
   * The explicit repaint is a safety net rather than the mechanism: a camera change on a map
   * that is otherwise idle does not always schedule a frame of its own, and the difference
   * between "the camera moved and drew" and "the camera moved and did not draw" is invisible
   * from the outside until someone tests it on a device. Asking for the frame costs nothing
   * and removes the whole class of failure.
   */
  fun moveCamera(options: CameraOptions) {
    map.cameraPosition = options.toCameraPosition(viewportPadding)
    map.triggerRepaint()
  }

  /**
   * Moves the camera over [durationMillis] rather than jumping to it.
   *
   * This is what an establishing shot uses. A jump goes straight to the native map with a
   * single `jumpTo`, which on the emulator this app was first run on updates the camera the
   * SDK reports without ever producing a frame — a static "frame this route" then looks like
   * a dead button, while the flyover, which re-issues its camera every frame, flies perfectly.
   * Animating re-issues the camera over time the way the flyover does, which is the closest
   * thing to that proven path and better suited to a framing change anyway.
   */
  fun animateCamera(options: CameraOptions, durationMillis: Int = DEFAULT_ANIMATION_MILLIS) {
    map.animateCamera(
      CameraUpdateFactory.newCameraPosition(options.toCameraPosition(viewportPadding)),
      durationMillis,
    )
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
   * An empty reveal is handled by hiding the route layers rather than by handing the
   * source an empty collection. MapLibre does not clear a GeoJSON source that way: the
   * geometry from the previous update stays on the map and keeps being drawn, so seeking
   * back to the start of a flight left the whole route sitting there while the intro
   * deliberately reveals nothing. Visibility is the switch that does what it says, and it
   * is set after the data so that the two cannot disagree.
   *
   * No-op until the style has loaded, or if the route source is absent, which is
   * the case before any route has been imported.
   */
  fun updateTrack(geoJson: String) {
    requestedRoute = geoJson
    map.getStyle { style -> drawRequestedRoute(style) }
  }

  /**
   * Replaces the gradient-coloured route.
   *
   * A separate source from [updateTrack] rather than a replacement for it, because the two
   * answer different questions: the plain line is where the route is, the gradient line is
   * how steep it is. A flyover has no gradient data to draw and shows the first; a planner
   * draws both, and the coloured line covers the plain one.
   *
   * Like [updateTrack], the latest request is what gets drawn, so a style that finishes
   * loading after the user has moved on replays the current route rather than a stale one.
   */
  fun updateGradientRoute(geoJson: String) {
    requestedGradientRoute = geoJson
    map.getStyle { style ->
      val latest = requestedGradientRoute ?: return@getStyle
      style.getSourceAs<GeoJsonSource>(MapStyleFactory.GRADIENT_SOURCE_ID)?.setGeoJson(latest)
      map.triggerRepaint()
    }
  }

  /** Replaces the drawn markers, which are the planner's waypoints or an import's POIs. */
  fun updateWaypoints(geoJson: String) {
    requestedWaypoints = geoJson
    map.getStyle { style ->
      val latest = requestedWaypoints ?: return@getStyle
      style.getSourceAs<GeoJsonSource>(MapStyleFactory.WAYPOINT_SOURCE_ID)?.setGeoJson(latest)
      map.triggerRepaint()
    }
  }

  /**
   * Installs a tap handler, replacing any previous one. Pass null to stop listening.
   *
   * The handler is a plain callback rather than a Compose state holder because taps arrive on
   * the map's own thread of control, independent of composition.
   */
  fun setOnMapClickListener(listener: ((LatLng) -> Unit)?) {
    mapClickListener?.let { map.removeOnMapClickListener(it) }
    mapClickListener = listener?.let { callback ->
      MapLibreMap.OnMapClickListener { point ->
        callback(LatLng(point.latitude, point.longitude))
        // Consume the tap: another listener acting on the same tap would be a surprise.
        true
      }.also { map.addOnMapClickListener(it) }
    }
  }

  /**
   * Draws the route the app last asked for, on whatever style is current.
   *
   * Deliberately not "the route this call was made with". A style is applied asynchronously,
   * and MapLibre holds on to the callback of a `getStyle` call made while one is loading, so
   * a request can be honoured long after it was made — after the flight has already moved on.
   * Replaying that request then paints a route the user has since seeked away from: the
   * import's whole route reappearing over an intro that reveals nothing, which is exactly the
   * state a first seek after importing lands in. Drawing the latest request instead makes a
   * late callback harmless, because the latest request is always the one on screen.
   */
  private fun drawRequestedRoute(style: Style) {
    val geoJson = requestedRoute ?: return
    val empty = geoJson.contains(NO_COORDINATES)

    style.getSourceAs<GeoJsonSource>(MapStyleFactory.TRACK_SOURCE_ID)
      ?.setGeoJson(if (empty) NO_TRACK else geoJson)

    // A source swap does not by itself schedule a frame. While a flight is running the next
    // camera move does it within a frame; stopped on a slider — which is how a user inspects
    // a flight — nothing else is moving, so the change would sit there unseen.
    map.triggerRepaint()
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
 * The GeoJSON to hand to the route source.
 *
 * A reveal of nothing encodes to a geometry with no coordinates, and the source is still
 * handed a valid empty collection rather than that, so that whatever is left in it is at
 * least well formed for the next update. Clearing the map is [routeVisibilityFor]'s job:
 * MapLibre does not drop the geometry a GeoJSON source already holds when it is given an
 * empty collection, so this alone would leave the previous reveal drawn.
 *
 * Kept as a pure function so the swap is pinned by a JVM test.
 */
internal fun trackPayloadFor(geoJson: String): String =
  if (geoJson.contains(NO_COORDINATES)) NO_TRACK else geoJson

/**
 * The camera padding for a given set of covered edges, or null when nothing is covered.
 *
 * MapLibre takes the four values in left, top, right, bottom order while the eye expects
 * top, right, bottom, left, and it takes them as doubles in the camera's own array. Both
 * are easy to get wrong in a way that compiles, and a transposed screen edge is a
 * mis-framed map rather than an error, so the order is pinned by a unit test.
 */
internal fun cameraPadding(left: Int, top: Int, right: Int, bottom: Int): DoubleArray? {
  if (left <= 0 && top <= 0 && right <= 0 && bottom <= 0) return null
  return doubleArrayOf(left.toDouble(), top.toDouble(), right.toDouble(), bottom.toDouble())
}

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
 * An encoded reveal of nothing: a geometry with no coordinates.
 *
 * [MapController.updateTrack] swaps this for [NO_TRACK], because MapLibre does not clear a
 * GeoJSON source when it is handed one of these.
 */
internal const val NO_COORDINATES = "\"coordinates\":[]"

/** The update MapLibre does clear a GeoJSON source with. */
internal const val NO_TRACK = """{"type":"FeatureCollection","features":[]}"""

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

/**
 * How long an establishing shot takes to settle.
 *
 * Long enough to read as a movement rather than a jump, short enough that it is over before
 * the user wonders whether anything happened.
 */
internal const val DEFAULT_ANIMATION_MILLIS = 450
